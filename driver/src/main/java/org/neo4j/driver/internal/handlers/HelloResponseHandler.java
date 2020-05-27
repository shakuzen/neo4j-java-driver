/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.driver.internal.handlers;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;

import java.util.Map;

import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.util.ServerVersion;
import org.neo4j.driver.Value;

import static org.neo4j.driver.internal.async.connection.ChannelAttributes.setConnectionId;
import static org.neo4j.driver.internal.async.connection.ChannelAttributes.setServerVersion;
import static org.neo4j.driver.internal.util.MetadataExtractor.extractNeo4jServerVersion;

public class HelloResponseHandler implements ResponseHandler
{
    private static final String CONNECTION_ID_METADATA_KEY = "connection_id";

    private final ChannelPromise connectionInitializedPromise;
    private final Channel channel;
    private final int protocolVersion;

    public HelloResponseHandler( ChannelPromise connectionInitializedPromise, int protocolVersion )
    {
        this.connectionInitializedPromise = connectionInitializedPromise;
        this.channel = connectionInitializedPromise.channel();
        this.protocolVersion = protocolVersion;
    }

    @Override
    public void onSuccess( Map<String,Value> metadata )
    {
        try
        {
            // From Server V4 extracting server from metadata in the success message is unreliable
            // so we fix the Server version against the Bolt Protocol version for Server V4 and above.
            if ( protocolVersion == 4 )
            {
                setServerVersion( channel, ServerVersion.v4_0_0 );
            }
            else
            {
                setServerVersion( channel, extractNeo4jServerVersion( metadata ) );
            }

            String connectionId = extractConnectionId( metadata );
            setConnectionId( channel, connectionId );

            connectionInitializedPromise.setSuccess();
        }
        catch ( Throwable error )
        {
            onFailure( error );
            throw error;
        }
    }

    @Override
    public void onFailure( Throwable error )
    {
        channel.close().addListener( future -> connectionInitializedPromise.setFailure( error ) );
    }

    @Override
    public void onRecord( Value[] fields )
    {
        throw new UnsupportedOperationException();
    }

    private static String extractConnectionId( Map<String,Value> metadata )
    {
        Value value = metadata.get( CONNECTION_ID_METADATA_KEY );
        if ( value == null || value.isNull() )
        {
            throw new IllegalStateException( "Unable to extract " + CONNECTION_ID_METADATA_KEY + " from a response to HELLO message. " +
                                             "Received metadata: " + metadata );
        }
        return value.asString();
    }
}
