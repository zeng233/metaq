package com.taobao.metamorphosis.client.extension.producer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.client.MetaMessageSessionFactory;
import com.taobao.metamorphosis.client.RemotingClientWrapper;
import com.taobao.metamorphosis.client.producer.PartitionSelector;
import com.taobao.metamorphosis.client.producer.ProducerZooKeeper;
import com.taobao.metamorphosis.client.producer.SendResult;
import com.taobao.metamorphosis.client.producer.SimpleMessageProducer;
import com.taobao.metamorphosis.cluster.Partition;
import com.taobao.metamorphosis.exception.MetaClientException;
import com.taobao.metamorphosis.exception.MetaOpeartionTimeoutException;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.utils.HexSupport;


/**
 * <pre>
 * ������Ϣ�����ߵ�ʵ����,��Ҫ������Ϣ����(����ĳ��id)ɢ�е��̶�������Ҫ������ĳ�����ʹ��.
 * ��Ԥ�ڵķ���������ʱ,��Ϣ�����浽����,��������ʱ�ָ�.
 * </pre>
 * 
 * @author �޻�
 * @since 2011-8-24 ����4:37:48
 */

public class OrderedMessageProducer extends SimpleMessageProducer {
    private static final Log log = LogFactory.getLog(OrderedMessageProducer.class);

    private final MessageRecoverManager localMessageStorageManager;
    private final OrderedMessageSender orderMessageSender;


    public OrderedMessageProducer(final MetaMessageSessionFactory messageSessionFactory,
            final RemotingClientWrapper remotingClient, final PartitionSelector partitionSelector,
            final ProducerZooKeeper producerZooKeeper, final String sessionId,
            final MessageRecoverManager localMessageStorageManager) {
        super(messageSessionFactory, remotingClient, partitionSelector, producerZooKeeper, sessionId);
        this.localMessageStorageManager = localMessageStorageManager;
        this.orderMessageSender = new OrderedMessageSender(this);
    }


    @Override
    public void publish(final String topic) {
        super.publish(topic);
        this.localMessageStorageManager.setMessageRecoverer(this.recoverer);
    }


    @Override
    public SendResult sendMessage(final Message message, final long timeout, final TimeUnit unit)
            throws MetaClientException, InterruptedException {
        this.checkState();
        this.checkMessage(message);
        return this.orderMessageSender.sendMessage(message, timeout, unit);
    }


    Partition selectPartition(final Message message) throws MetaClientException {
        return this.producerZooKeeper.selectPartition(message.getTopic(), message, this.partitionSelector);
    }


    SendResult saveMessageToLocal(final Message message, final Partition partition, final long timeout,
            final TimeUnit unit) throws MetaClientException {
        
        boolean store = this.messageSessionFactory.getMetaClientConfig().isStoreMessageToLocal();
        if(!store) {
            throw new MetaClientException("send message to server error(storeMessageToLocal = false)");
        }
        
        try {
            this.localMessageStorageManager.append(message, partition);
            return new SendResult(true, partition, -1, "send to local");
        }
        catch (final IOException e) {
            log.error("send message to local failed,topic=" + message.getTopic() + ",content["
                    + HexSupport.toHexFromBytes(message.getData()) + "]");
            return new SendResult(false, null, -1, "send message to local failed");
        }
    }

    private final boolean sendFailAndSaveToLocal = Boolean.parseBoolean(System.getProperty(
        "meta.ordered.saveToLocalWhenFailed", "false"));


    SendResult sendMessageToServer(final Message message, final long timeout, final TimeUnit unit,
            final boolean saveToLocalWhileForbidden) throws MetaClientException, InterruptedException,
            MetaOpeartionTimeoutException {
        final SendResult sendResult = this.sendMessageToServer(message, timeout, unit);
        if (this.needSaveToLocalWhenSendFailed(sendResult)
                || this.needSaveToLocalWhenForbidden(saveToLocalWhileForbidden, sendResult)) {
            log.warn("send to server fail,save to local." + sendResult.getErrorMessage());
            return this.saveMessageToLocal(message, Partition.RandomPartiton, timeout, unit);
        }
        else {
            return sendResult;
        }
    }


    private boolean needSaveToLocalWhenSendFailed(final SendResult sendResult) {
        return !sendResult.isSuccess() && sendFailAndSaveToLocal;
    }


    private boolean needSaveToLocalWhenForbidden(final boolean saveToLocalWhileForbidden, final SendResult sendResult) {
        return !sendResult.isSuccess() && sendResult.getErrorMessage().equals(String.valueOf(HttpStatus.Forbidden))
                && saveToLocalWhileForbidden;
    }


    int getLocalMessageCount(final String topic, final Partition partition) {
        return this.localMessageStorageManager.getMessageCount(topic, partition);
    }


    void tryRecoverMessage(final String topic, final Partition partition) {
        this.localMessageStorageManager.recover(topic, partition, this.recoverer);
    }

    private final MessageRecoverManager.MessageRecoverer recoverer = new MessageRecoverManager.MessageRecoverer() {

        @Override
        public void handle(final Message msg) throws Exception {
            final SendResult sendResult =
                    OrderedMessageProducer.this.sendMessageToServer(msg, DEFAULT_OP_TIMEOUT, TimeUnit.MILLISECONDS);
            // �ָ�ʱ����ʧ��,�׳��쳣ֹͣ������Ϣ�Ļָ�
            if (!sendResult.isSuccess() /*
                                         * &&
                                         * sendResult.getErrorMessage().equals
                                         * (String
                                         * .valueOf(HttpStatus.Forbidden))
                                         */) {
                throw new MetaClientException(sendResult.getErrorMessage());
            }
        }

    };
}