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
package neo4j.org.testkit.backend.messages.requests;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import neo4j.org.testkit.backend.TestkitState;
import neo4j.org.testkit.backend.holder.DriverHolder;
import neo4j.org.testkit.backend.messages.responses.DomainNameResolutionRequired;
import neo4j.org.testkit.backend.messages.responses.Driver;
import neo4j.org.testkit.backend.messages.responses.DriverError;
import neo4j.org.testkit.backend.messages.responses.ResolverResolutionRequired;
import neo4j.org.testkit.backend.messages.responses.TestkitCallback;
import neo4j.org.testkit.backend.messages.responses.TestkitResponse;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.internal.BoltServerAddress;
import org.neo4j.driver.internal.DefaultDomainNameResolver;
import org.neo4j.driver.internal.DomainNameResolver;
import org.neo4j.driver.internal.DriverFactory;
import org.neo4j.driver.internal.SecuritySettings;
import org.neo4j.driver.internal.cluster.RoutingSettings;
import org.neo4j.driver.internal.cluster.loadbalancing.LoadBalancer;
import org.neo4j.driver.internal.retry.RetrySettings;
import org.neo4j.driver.internal.security.SecurityPlan;
import org.neo4j.driver.net.ServerAddressResolver;

@Setter
@Getter
public class NewDriver implements TestkitRequest
{
    private NewDriverBody data;

    @Override
    public TestkitResponse process( TestkitState testkitState )
    {
        String id = testkitState.newId();

        AuthToken authToken;
        switch ( data.getAuthorizationToken().getTokens().getScheme() )
        {
        case "basic":
            authToken = AuthTokens.basic( data.authorizationToken.getTokens().getPrincipal(),
                                          data.authorizationToken.getTokens().getCredentials(),
                                          data.authorizationToken.getTokens().getRealm() );
            break;
        case "bearer":
            authToken = AuthTokens.bearer( data.authorizationToken.getTokens().getCredentials() );
            break;
        case "kerberos":
            authToken = AuthTokens.kerberos( data.authorizationToken.getTokens().getCredentials() );
            break;
        default:
            authToken = AuthTokens.custom(
                    data.authorizationToken.getTokens().getPrincipal(),
                    data.authorizationToken.getTokens().getCredentials(),
                    data.authorizationToken.getTokens().getRealm(),
                    data.authorizationToken.getTokens().getScheme(),
                    data.authorizationToken.getTokens().getParameters()
            );
            break;
        }

        Config.ConfigBuilder configBuilder = Config.builder();
        if ( data.isResolverRegistered() )
        {
            configBuilder.withResolver( callbackResolver( testkitState ) );
        }
        DomainNameResolver domainNameResolver = DefaultDomainNameResolver.getInstance();
        if ( data.isDomainNameResolverRegistered() )
        {
            domainNameResolver = callbackDomainNameResolver( testkitState );
        }
        Optional.ofNullable( data.userAgent ).ifPresent( configBuilder::withUserAgent );
        Optional.ofNullable( data.connectionTimeoutMs ).ifPresent( timeout -> configBuilder.withConnectionTimeout( timeout, TimeUnit.MILLISECONDS ) );
        Optional.ofNullable( data.fetchSize ).ifPresent( configBuilder::withFetchSize );
        RetrySettings retrySettings = Optional.ofNullable( data.maxTxRetryTimeMs )
                                              .map( RetrySettings::new )
                                              .orElse( RetrySettings.DEFAULT );
        org.neo4j.driver.Driver driver;
        Config config = configBuilder.build();
        try
        {
            driver = driver( URI.create( data.uri ), authToken, config, retrySettings, domainNameResolver, testkitState, id );
        }
        catch ( RuntimeException e )
        {
            return handleExceptionAsErrorResponse( testkitState, e ).orElseThrow( () -> e );
        }
        testkitState.addDriverHolder( id, new DriverHolder( driver, config ) );
        return Driver.builder().data( Driver.DriverBody.builder().id( id ).build() ).build();
    }

    @Override
    public CompletionStage<TestkitResponse> processAsync( TestkitState testkitState )
    {
        return CompletableFuture.completedFuture( process( testkitState ) );
    }

    @Override
    public Mono<TestkitResponse> processRx( TestkitState testkitState )
    {
        return Mono.fromCompletionStage( processAsync( testkitState ) );
    }

    private ServerAddressResolver callbackResolver( TestkitState testkitState )
    {
        return address ->
        {
            String callbackId = testkitState.newId();
            ResolverResolutionRequired.ResolverResolutionRequiredBody body =
                    ResolverResolutionRequired.ResolverResolutionRequiredBody.builder()
                                                                             .id( callbackId )
                                                                             .address( String.format( "%s:%d", address.host(), address.port() ) )
                                                                             .build();
            ResolverResolutionRequired response =
                    ResolverResolutionRequired.builder()
                                              .data( body )
                                              .build();
            CompletionStage<TestkitCallbackResult> c = dispatchTestkitCallback( testkitState, response );
            ResolverResolutionCompleted resolutionCompleted;
            try
            {
                resolutionCompleted = (ResolverResolutionCompleted) c.toCompletableFuture().get();
            }
            catch ( Exception e )
            {
                throw new RuntimeException( e );
            }
            return resolutionCompleted.getData().getAddresses()
                                      .stream()
                                      .map( BoltServerAddress::new )
                                      .collect( Collectors.toCollection( LinkedHashSet::new ) );
        };
    }

    private DomainNameResolver callbackDomainNameResolver( TestkitState testkitState )
    {
        return address ->
        {
            String callbackId = testkitState.newId();
            DomainNameResolutionRequired.DomainNameResolutionRequiredBody body =
                    DomainNameResolutionRequired.DomainNameResolutionRequiredBody.builder()
                                                                                 .id( callbackId )
                                                                                 .name( address )
                                                                                 .build();
            DomainNameResolutionRequired callback =
                    DomainNameResolutionRequired.builder()
                                                .data( body )
                                                .build();

            CompletionStage<TestkitCallbackResult> callbackStage = dispatchTestkitCallback( testkitState, callback );
            DomainNameResolutionCompleted resolutionCompleted;
            try
            {
                resolutionCompleted = (DomainNameResolutionCompleted) callbackStage.toCompletableFuture().get();
            }
            catch ( Exception e )
            {
                throw new RuntimeException( "Unexpected failure during Testkit callback", e );
            }

            return resolutionCompleted.getData().getAddresses()
                                      .stream()
                                      .map(
                                              addr ->
                                              {
                                                  try
                                                  {
                                                      return InetAddress.getByName( addr );
                                                  }
                                                  catch ( UnknownHostException e )
                                                  {
                                                      throw new RuntimeException( e );
                                                  }
                                              } )
                                      .toArray( InetAddress[]::new );
        };
    }

    private CompletionStage<TestkitCallbackResult> dispatchTestkitCallback( TestkitState testkitState, TestkitCallback response )
    {
        CompletableFuture<TestkitCallbackResult> future = new CompletableFuture<>();
        testkitState.getCallbackIdToFuture().put( response.getCallbackId(), future );
        testkitState.getResponseWriter().accept( response );
        return future;
    }

    private org.neo4j.driver.Driver driver( URI uri, AuthToken authToken, Config config, RetrySettings retrySettings, DomainNameResolver domainNameResolver,
                                            TestkitState testkitState,
                                            String driverId )
    {
        RoutingSettings routingSettings = RoutingSettings.DEFAULT;
        SecuritySettings.SecuritySettingsBuilder securitySettingsBuilder = new SecuritySettings.SecuritySettingsBuilder();
        SecuritySettings securitySettings = securitySettingsBuilder.build();
        SecurityPlan securityPlan = securitySettings.createSecurityPlan( uri.getScheme() );
        return new DriverFactoryWithDomainNameResolver( domainNameResolver, testkitState, driverId )
                .newInstance( uri, authToken, routingSettings, retrySettings, config, securityPlan );
    }

    private Optional<TestkitResponse> handleExceptionAsErrorResponse( TestkitState testkitState, RuntimeException e )
    {
        Optional<TestkitResponse> response = Optional.empty();
        if ( e instanceof IllegalArgumentException && e.getMessage().startsWith( DriverFactory.NO_ROUTING_CONTEXT_ERROR_MESSAGE ) )
        {
            String id = testkitState.newId();
            String errorType = e.getClass().getName();
            response = Optional.of(
                    DriverError.builder().data( DriverError.DriverErrorBody.builder().id( id ).errorType( errorType ).build() ).build()
            );
        }
        return response;
    }

    @Setter
    @Getter
    public static class NewDriverBody
    {
        private String uri;
        private AuthorizationToken authorizationToken;
        private String userAgent;
        private boolean resolverRegistered;
        private boolean domainNameResolverRegistered;
        private Long connectionTimeoutMs;
        private Integer fetchSize;
        private Long maxTxRetryTimeMs;
    }

    @RequiredArgsConstructor
    private static class DriverFactoryWithDomainNameResolver extends DriverFactory
    {
        private final DomainNameResolver domainNameResolver;
        private final TestkitState testkitState;
        private final String driverId;

        @Override
        protected DomainNameResolver getDomainNameResolver()
        {
            return domainNameResolver;
        }

        @Override
        protected void handleNewLoadBalancer( LoadBalancer loadBalancer )
        {
            testkitState.getRoutingTableRegistry().put( driverId, loadBalancer.getRoutingTableRegistry() );
        }
    }
}
