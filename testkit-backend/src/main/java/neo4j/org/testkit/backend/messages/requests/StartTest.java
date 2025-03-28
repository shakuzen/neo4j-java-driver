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
import lombok.Setter;
import neo4j.org.testkit.backend.TestkitState;
import neo4j.org.testkit.backend.messages.responses.RunTest;
import neo4j.org.testkit.backend.messages.responses.SkipTest;
import neo4j.org.testkit.backend.messages.responses.TestkitResponse;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Setter
@Getter
public class StartTest implements TestkitRequest
{
    private static final Map<String,String> ASYNC_SKIP_PATTERN_TO_REASON = new HashMap<>();
    private static final Map<String,String> REACTIVE_SKIP_PATTERN_TO_REASON = new HashMap<>();

    static
    {
        ASYNC_SKIP_PATTERN_TO_REASON.put( "^.*\\.test_should_reject_server_using_verify_connectivity_bolt_3x0$", "Does not error as expected" );

        // Current limitations (require further investigation or bug fixing)
        String skipMessage = "Does not report RUN FAILURE";
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.Routing[^.]+\\.test_should_write_successfully_on_leader_switch_using_tx_function$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDisconnects\\.test_disconnect_after_hello$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDisconnects\\.test_disconnect_session_on_run$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDisconnects\\.test_disconnect_on_tx_run$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestSessionRun\\.test_raises_error_on_session_run$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestTxRun\\.test_raises_error_on_tx(_func)?_run", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestTxRun\\.test_failed_tx_run_allows(_skipping)?_rollback", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestAuthorizationV\\dx\\d\\.test_should_fail_with_auth_expired_on_run_using_tx_run$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestAuthorizationV\\dx\\d\\.test_should_fail_with_token_expired_on_run_using_tx_run$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDirectConnectionRecvTimeout\\.test_timeout$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDirectConnectionRecvTimeout\\.test_timeout_unmanaged_tx$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDirectConnectionRecvTimeout\\.test_timeout_unmanaged_tx_should_fail_subsequent_usage_after_timeout$",
                                             skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestDirectConnectionRecvTimeout\\.test_timeout_managed_tx_retry$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestRoutingConnectionRecvTimeout\\.test_timeout$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestRoutingConnectionRecvTimeout\\.test_timeout_unmanaged_tx$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestRoutingConnectionRecvTimeout\\.test_timeout_unmanaged_tx_should_fail_subsequent_usage_after_timeout$",
                                             skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestRoutingConnectionRecvTimeout\\.test_timeout_managed_tx_retry$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestTxRun\\.test_broken_transaction_should_not_break_session$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestTxRun\\.test_does_not_update_last_bookmark_on_failure$", skipMessage );
        skipMessage = "Does not support multiple concurrent result streams on session level";
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestSessionRun\\.test_iteration_nested$", skipMessage );
        REACTIVE_SKIP_PATTERN_TO_REASON.put( "^.*\\.TestSessionRun\\.test_partial_iteration$", skipMessage );
    }

    private StartTestBody data;

    @Override
    public TestkitResponse process( TestkitState testkitState )
    {
        return RunTest.builder().build();
    }

    @Override
    public CompletionStage<TestkitResponse> processAsync( TestkitState testkitState )
    {
        TestkitResponse testkitResponse = ASYNC_SKIP_PATTERN_TO_REASON
                .entrySet()
                .stream()
                .filter( entry -> data.getTestName().matches( entry.getKey() ) )
                .findFirst()
                .map( entry -> (TestkitResponse) SkipTest.builder()
                                                         .data( SkipTest.SkipTestBody.builder()
                                                                                     .reason( entry.getValue() )
                                                                                     .build() )
                                                         .build() )
                .orElseGet( () -> RunTest.builder().build() );

        return CompletableFuture.completedFuture( testkitResponse );
    }

    @Override
    public Mono<TestkitResponse> processRx( TestkitState testkitState )
    {
        TestkitResponse testkitResponse = REACTIVE_SKIP_PATTERN_TO_REASON
                .entrySet()
                .stream()
                .filter( entry -> data.getTestName().matches( entry.getKey() ) )
                .findFirst()
                .map( entry -> (TestkitResponse) SkipTest.builder()
                                                         .data( SkipTest.SkipTestBody.builder()
                                                                                     .reason( entry.getValue() )
                                                                                     .build() )
                                                         .build() )
                .orElseGet( () -> RunTest.builder().build() );

        return Mono.fromCompletionStage( CompletableFuture.completedFuture( testkitResponse ) );
    }

    @Setter
    @Getter
    public static class StartTestBody
    {
        private String testName;
    }
}
