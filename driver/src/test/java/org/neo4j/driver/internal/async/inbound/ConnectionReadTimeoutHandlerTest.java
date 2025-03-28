/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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

import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.neo4j.driver.exceptions.ConnectionReadTimeoutException;

import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;
import static org.mockito.Mockito.mock;

public class ConnectionReadTimeoutHandlerTest
{
    ConnectionReadTimeoutHandler handler = new ConnectionReadTimeoutHandler( 15L, TimeUnit.SECONDS );
    ChannelHandlerContext context = mock( ChannelHandlerContext.class );

    @Test
    void shouldFireConnectionReadTimeoutExceptionAndCloseChannelOnReadTimeOutOnce()
    {
        // WHEN
        IntStream.range( 0, 10 ).forEach( i -> handler.readTimedOut( context ) );

        // THEN
        then( context ).should( times( 1 ) ).fireExceptionCaught( any( ConnectionReadTimeoutException.class ) );
        then( context ).should( times( 1 ) ).close();
    }
}
