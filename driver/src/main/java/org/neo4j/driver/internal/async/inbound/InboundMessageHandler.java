/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.internal.async.inbound;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;

import org.neo4j.driver.internal.logging.PrefixedLogger;
import org.neo4j.driver.internal.messaging.MessageFormat;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.Logging;

import static io.netty.buffer.ByteBufUtil.prettyHexDump;
import static java.util.Objects.requireNonNull;
import static org.neo4j.driver.internal.async.ChannelAttributes.messageDispatcher;

public class InboundMessageHandler extends SimpleChannelInboundHandler<ByteBuf>
{
    private final ByteBufInput input;
    private final MessageFormat.Reader reader;
    private final Logging logging;

    private InboundMessageDispatcher messageDispatcher;
    private Logger log;

    public InboundMessageHandler( MessageFormat messageFormat, Logging logging )
    {
        this.input = new ByteBufInput();
        this.reader = messageFormat.newReader( input );
        this.logging = logging;
    }

    @Override
    public void handlerAdded( ChannelHandlerContext ctx )
    {
        messageDispatcher = requireNonNull( messageDispatcher( ctx.channel() ) );
        log = new PrefixedLogger( ctx.channel().toString(), logging, getClass() );
    }

    @Override
    public void handlerRemoved( ChannelHandlerContext ctx )
    {
        messageDispatcher = null;
        log = null;
    }

    @Override
    protected void channelRead0( ChannelHandlerContext ctx, ByteBuf msg )
    {
        if ( messageDispatcher.fatalErrorOccurred() )
        {
            log.warn( "Message ignored because of the previous fatal error. Channel will be closed. Message:\n%s",
                    prettyHexDump( msg ) );
            return;
        }

        if ( log.isTraceEnabled() )
        {
            log.trace( "S:\n%s", prettyHexDump( msg ) );
        }

        input.start( msg );
        try
        {
            reader.read( messageDispatcher );
        }
        catch ( Throwable error )
        {
            throw new DecoderException( "Failed to read inbound message:\n" + prettyHexDump( msg ) + "\n", error );
        }
        finally
        {
            input.stop();
        }
    }
}
