/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
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

package bt.net.pipeline;

import bt.net.Peer;
import bt.net.buffer.BufferMutator;
import bt.protocol.Message;
import bt.protocol.handler.MessageHandler;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class DefaultChannelPipeline implements ChannelPipeline {

    private final MessageDeserializer deserializer;
    private final MessageSerializer serializer;

    private final ByteBuffer inboundBuffer;
    private final ByteBuffer outboundBuffer;
    private final List<BufferMutator> decoders;
    private final List<BufferMutator> encoders;

    private final Queue<Message> inboundQueue;

    // inbound buffer parameters
    private int decodedDataOffset;
    private int undecodedDataOffset;
    private int undecodedDataLimit;

    private ChannelHandlerContext context;

    public DefaultChannelPipeline(
            Peer peer,
            MessageHandler<Message> protocol,
            ByteBuffer inboundBuffer,
            ByteBuffer outboundBuffer,
            List<BufferMutator> decoders,
            List<BufferMutator> encoders) {

        this.deserializer = new MessageDeserializer(peer, protocol);
        this.serializer = new MessageSerializer(peer, protocol);
        this.inboundBuffer = inboundBuffer;
        this.undecodedDataLimit = inboundBuffer.position();
        this.outboundBuffer = outboundBuffer;
        this.decoders = decoders;
        this.encoders = encoders;
        this.inboundQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public Message receive() {
        checkHandlerIsBound();

        return inboundQueue.poll();
    }

    private void fireDataReceived() {
        if (undecodedDataOffset < undecodedDataLimit) {
            inboundBuffer.limit(undecodedDataLimit);
            decoders.forEach(mutator -> {
                inboundBuffer.position(undecodedDataOffset);
                mutator.mutate(inboundBuffer);
            });
            undecodedDataOffset = undecodedDataLimit;

            inboundBuffer.position(decodedDataOffset);
            inboundBuffer.limit(undecodedDataOffset);
            Message message;
            for (;;) {
                message = deserializer.deserialize(inboundBuffer);
                if (message == null) {
                    break;
                } else {
                    inboundQueue.add(message);
                    decodedDataOffset = inboundBuffer.position();
                }
            }
        }
    }

    @Override
    public boolean send(Message message) {
        checkHandlerIsBound();

        int position = outboundBuffer.position();
        boolean serialized = serializer.serialize(message, outboundBuffer);
        if (serialized) {
            encoders.forEach(mutator -> {
                outboundBuffer.position(position);
                mutator.mutate(outboundBuffer);
            });
        }
        outboundBuffer.position(position);
        return serialized;
    }

    private void checkHandlerIsBound() {
        if (context == null) {
            throw new IllegalStateException("Channel handler is not bound");
        }
    }

    @Override
    public ChannelHandlerContext bindHandler(ChannelHandler handler) {
        if (context != null) {
            if (handler == context.handler()) {
                return context;
            } else {
                throw new IllegalStateException("Already bound to different handler");
            }
        }

        context = new ChannelHandlerContext() {
            @Override
            public ChannelHandler handler() {
                return handler;
            }

            @Override
            public void fireDataReceived() {
                DefaultChannelPipeline.this.fireDataReceived();
            }

            @Override
            public void fireDataSent() {
                handler.tryFlush();
            }
        };
        return context;
    }
}