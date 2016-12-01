/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package enmasse.mqtt;

import enmasse.mqtt.messages.AmqpHelper;
import enmasse.mqtt.messages.AmqpPublishMessage;
import enmasse.mqtt.messages.AmqpPubrelMessage;
import enmasse.mqtt.messages.AmqpSubscribeMessage;
import enmasse.mqtt.messages.AmqpTopicSubscription;
import enmasse.mqtt.messages.AmqpUnsubscribeMessage;
import enmasse.mqtt.messages.AmqpWillMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock for a "broker like" component
 */
public class MockBroker extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MockBroker.class);

    private static final String CONTAINER_ID = "broker";

    // event bus names for communication between Subscription Service and broker
    public static final String EB_SUBSCRIBE = "subscribe";
    public static final String EB_UNSUBSCRIBE = "unsubscribe";
    public static final String EB_WILL = "will";

    // header field and related action available for interacting with the event bus "will"
    public static final String EB_WILL_ACTION_HEADER = "will-action";
    public static final String EB_WILL_ACTION_ADD = "will-add";
    public static final String EB_WILL_ACTION_CLEAR = "will-clear";
    public static final String EB_WILL_ACTION_DELIVERY = "will-delivery";

    // topic -> receiver
    private Map<String, ProtonReceiver> receivers;
    // client-id -> sender (to $mqtt.to.<client-id>)
    private Map<String, ProtonSender> senders;
    // topic -> client-id lists (subscribers)
    private Map<String, List<String>> subscriptions;
    // topic -> retained message
    private Map<String, AmqpPublishMessage> retained;
    // receiver link name -> will message
    private Map<String, AmqpWillMessage> wills;
    // client-id -> receiver (to $mqtt.<client-id>.pubrel)
    private Map<String, ProtonReceiver> receiversPubrel;

    private String connectAddress;
    private int connectPort;

    private ProtonClient client;
    private ProtonConnection connection;

    private List<String> topics;

    /**
     * Constructor
     */
    public MockBroker() {

        this.receivers = new HashMap<>();
        this.senders = new HashMap<>();
        this.subscriptions = new HashMap<>();
        this.retained = new HashMap<>();
        this.topics = Arrays.asList("my_topic", "will");
        this.wills = new HashMap<>();
        this.receiversPubrel = new HashMap<>();
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {

        this.client = ProtonClient.create(this.vertx);

        this.client.connect(this.connectAddress, this.connectPort, done -> {

            if (done.succeeded()) {

                LOG.info("Broker started successfully ...");

                this.connection = done.result();
                this.connection.setContainer(CONTAINER_ID);

                this.connection
                        .sessionOpenHandler(session -> session.open())
                        .open();

                // attach receivers of pre-configured topics
                for (String topic: this.topics) {

                    ProtonReceiver receiver = this.connection.createReceiver(topic);

                    receiver
                            .setQoS(ProtonQoS.AT_LEAST_ONCE)
                            .setTarget(receiver.getRemoteTarget())
                            .handler((delivery, message) -> {

                                this.messageHandler(receiver, delivery, message);
                            })
                            .open();

                    this.receivers.put(topic, receiver);
                }

                // consumer for SUBSCRIBE requests from the Subscription Service
                this.vertx.eventBus().consumer(EB_SUBSCRIBE, ebMessage -> {

                    // the request object is exchanged through the map using messageId in the event bus message
                    Object obj = this.vertx.sharedData().getLocalMap(EB_SUBSCRIBE).remove(ebMessage.body());

                    if (obj instanceof AmqpSubscribeData) {

                        AmqpSubscribeMessage amqpSubscribeMessage = ((AmqpSubscribeData) obj).subscribe();
                        List<MqttQoS> grantedQoSLevels = this.subscribe(amqpSubscribeMessage);

                        // build the reply message body with granted QoS levels (JSON encoded)
                        JsonArray jsonArray = new JsonArray();
                        for (MqttQoS qos: grantedQoSLevels) {
                            jsonArray.add(qos);
                        }

                        // reply to the SUBSCRIBE request; the Subscription Service can send SUBACK
                        ebMessage.reply(jsonArray, replyDone -> {

                            // after sending SUBACK, Subscription Service reply in order to have mock broker
                            // sending retained message for topic subscriptions
                            if (replyDone.succeeded()) {

                                if (!this.retained.isEmpty()) {

                                    for (AmqpTopicSubscription amqpTopicSubscription: amqpSubscribeMessage.topicSubscriptions()) {

                                        if (this.retained.containsKey(amqpTopicSubscription.topic())) {

                                            AmqpPublishMessage amqpPublishMessage = this.retained.get(amqpTopicSubscription.topic());
                                            // QoS already set at AT_LEAST_ONCE as requested by the receiver side
                                            this.senders.get(amqpSubscribeMessage.clientId()).send(amqpPublishMessage.toAmqp());
                                        }
                                    }

                                }
                            }
                        });
                    }
                });

                // consumer for UNSUBSCRIBE requests from the Subscription Service
                this.vertx.eventBus().consumer(EB_UNSUBSCRIBE, ebMessage -> {

                    // the request object is exchanged through the map using messageId in the event bus message
                    Object obj = this.vertx.sharedData().getLocalMap(EB_UNSUBSCRIBE).remove(ebMessage.body());

                    if (obj instanceof  AmqpUnsubscribeData) {

                        AmqpUnsubscribeMessage amqpUnsubscribeMessage = ((AmqpUnsubscribeData) obj).unsubscribe();
                        this.unsubscribe(amqpUnsubscribeMessage);
                        ebMessage.reply(null);
                    }
                });

                // consumer for will requests from Will Service
                this.vertx.eventBus().consumer(EB_WILL, ebMessage -> {

                    String willAction = ebMessage.headers().get(EB_WILL_ACTION_HEADER);

                    switch (willAction) {

                        case EB_WILL_ACTION_ADD:

                            // get the AMQP_WILL using the client link name from the message body as key
                            Object obj = this.vertx.sharedData().getLocalMap(EB_WILL).remove(ebMessage.body());

                            if (obj instanceof AmqpWillData) {

                                AmqpWillMessage amqpWillMessage = ((AmqpWillData) obj).will();
                                this.wills.put((String) ebMessage.body(), amqpWillMessage);

                                ebMessage.reply(null);
                            }
                            break;

                        case EB_WILL_ACTION_CLEAR:

                            // clear the will using the client link name as key
                            if (this.wills.containsKey(ebMessage.body())) {
                                this.wills.remove(ebMessage.body());

                                ebMessage.reply(null);
                            }
                            break;

                        case EB_WILL_ACTION_DELIVERY:

                            // the requested client link name has a will to publish
                            if (this.wills.containsKey(ebMessage.body())) {

                                AmqpWillMessage amqpWillMessage = this.wills.get(ebMessage.body());

                                AmqpPublishMessage amqpPublishMessage =
                                        new AmqpPublishMessage(1, amqpWillMessage.qos(), false, amqpWillMessage.isRetain(), amqpWillMessage.topic(), amqpWillMessage.payload());

                                ProtonSender sender = this.connection.createSender(amqpPublishMessage.topic());

                                // TODO: it should be always AT_LEAST_ONCE
                                ProtonQoS protonQos = ProtonQoS.AT_LEAST_ONCE;
                                sender.setQoS(protonQos);

                                sender.open();

                                sender.send(amqpPublishMessage.toAmqp(), delivery -> {

                                    // true ... will published
                                    ebMessage.reply(true);
                                });

                            } else {

                                // false ... will not published (but request handled)
                                ebMessage.reply(false);
                            }
                            break;
                    }

                });

                startFuture.complete();

            } else {

                LOG.info("Error starting the broker ...", done.cause());

                startFuture.fail(done.cause());
            }
        });
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {

        this.connection.close();
        LOG.info("Broker has been shut down successfully");
        stopFuture.complete();
    }

    /**
     * Handle a subscription request
     *
     * @param amqpSubscribeMessage  AMQP_SUBSCRIBE message with subscribe request
     * @return  granted QoS levels
     */
    public List<MqttQoS> subscribe(AmqpSubscribeMessage amqpSubscribeMessage) {

        List<MqttQoS> grantedQoSLevels = new ArrayList<>();

        for (AmqpTopicSubscription amqpTopicSubscription: amqpSubscribeMessage.topicSubscriptions()) {

            // create a receiver for getting messages from the requested topic
            if (!this.receivers.containsKey(amqpTopicSubscription.topic())) {

                ProtonReceiver receiver = this.connection.createReceiver(amqpTopicSubscription.topic());

                // TODO: check QoS, always AT_LEAST_ONCE ?
                receiver
                        .setTarget(receiver.getRemoteTarget())
                        .handler((delivery, message) -> {

                            this.messageHandler(receiver, delivery, message);
                        })
                        .open();

                this.receivers.put(amqpTopicSubscription.topic(), receiver);
            }

            // create a sender to the unique client address for forwarding
            // messages when received on requested topic
            if (!this.senders.containsKey(amqpSubscribeMessage.clientId())) {

                ProtonSender sender = this.connection.createSender(String.format(AmqpHelper.AMQP_CLIENT_ADDRESS_TEMPLATE, amqpSubscribeMessage.clientId()));

                // QoS AT_LEAST_ONCE as requested by the receiver side
                sender.setQoS(ProtonQoS.AT_LEAST_ONCE)
                        .open();

                this.senders.put(amqpSubscribeMessage.clientId(), sender);
            }

            // create a receiver for the PUBREL client address for receiving
            // such messages (handling QoS 2)
            if (!this.receiversPubrel.containsKey(amqpSubscribeMessage.clientId())) {

                ProtonReceiver receiver = this.connection.createReceiver(String.format(AmqpHelper.AMQP_CLIENT_PUBREL_ADDRESS_TEMPLATE, amqpSubscribeMessage.clientId()));

                // TODO: check QoS, always AT_LEAST_ONCE ?
                receiver
                        .setTarget(receiver.getRemoteTarget())
                        .handler((delivery, message) -> {

                            this.messageHandler(receiver, delivery, message);
                        })
                        .open();

                this.receiversPubrel.put(amqpSubscribeMessage.clientId(), receiver);
            }

            // add the subscription to the requested topic by the client identifier
            if (!this.subscriptions.containsKey(amqpTopicSubscription.topic())) {

                this.subscriptions.put(amqpTopicSubscription.topic(), new ArrayList<>());
            }

            this.subscriptions.get(amqpTopicSubscription.topic()).add(amqpSubscribeMessage.clientId());

            // just as mock all requested QoS levels are granted
            grantedQoSLevels.add(amqpTopicSubscription.qos());
        }

        return grantedQoSLevels;
    }

    /**
     * Handle an unsubscription request
     *
     * @param amqpUnsubscribeMessage  AMQP_UNSUBSCRIBE message with unsubscribe request
     */
    public void unsubscribe(AmqpUnsubscribeMessage amqpUnsubscribeMessage) {

        for (String topic: amqpUnsubscribeMessage.topics()) {

            this.subscriptions.get(topic).remove(amqpUnsubscribeMessage.clientId());

            if (this.subscriptions.get(topic).size() == 0) {
                this.subscriptions.remove(topic);
            }
        }
    }

    private void messageHandler(ProtonReceiver receiver, ProtonDelivery delivery, Message message) {

        if (message.getSubject() != null) {

            switch (message.getSubject()) {

                case AmqpPubrelMessage.AMQP_SUBJECT:

                    {
                        AmqpPubrelMessage amqpPubrelMessage = AmqpPubrelMessage.from(message);
                        delivery.disposition(Accepted.getInstance(), true);

                        String address = receiver.getSource().getAddress();

                        String clientId = AmqpHelper.getClientIdFromPubrelAddress(address);

                        // QoS already set at AT_LEAST_ONCE as requested by the receiver side
                        this.senders.get(clientId).send(message);
                    }
                    break;

                case AmqpPublishMessage.AMQP_SUBJECT:

                    {

                        String topic = receiver.getSource().getAddress();

                        // TODO: what when raw AMQP message hasn't "publish" as subject ??

                        // check if it's retained
                        AmqpPublishMessage amqpPublishMessage = AmqpPublishMessage.from(message);
                        if (amqpPublishMessage.isRetain()) {
                            this.retained.put(amqpPublishMessage.topic(), amqpPublishMessage);
                        }

                        delivery.disposition(Accepted.getInstance(), true);

                        List<String> subscribers = this.subscriptions.get(topic);

                        if (subscribers != null) {

                            for (String clientId : subscribers) {

                                // QoS already set at AT_LEAST_ONCE as requested by the receiver side
                                this.senders.get(clientId).send(message);

                            }
                        }
                    }
                    break;
            }

        }

    }

    /**
     * Set the address for connecting to the AMQP services
     *
     * @param connectAddress    address for AMQP connections
     * @return  current Mock broker instance
     */
    public MockBroker setConnectAddress(String connectAddress) {
        this.connectAddress = connectAddress;
        return this;
    }

    /**
     * Set the port for connecting to the AMQP services
     *
     * @param connectPort   port for AMQP connections
     * @return  current Mock broker instance
     */
    public MockBroker setConnectPort(int connectPort) {
        this.connectPort = connectPort;
        return this;
    }

}
