$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $PSScriptRoot "log"
$reference = Join-Path $projectRoot "references\responses-midstream-retry-cases.json"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$groundTruth = [ordered]@{ cases = @(
    [ordered]@{ name='visible_output_disconnect_fails_without_replay'; protocol='responses'; upstream_attempts=@('visible_delta_then_premature_close'); expected_log_status=502; expected_retry_count=0; expected_client_terminal_event='response.failed'; expected_client_content='partial output is visible'; expected_billing_settlements=1 },
    [ordered]@{ name='sticky_cache_and_session_affinity_escape'; protocol='responses'; request_fields=@('prompt_cache_key','prompt_cache_retention','client_metadata'); attempt_1='original_request'; attempt_2='cache_fields_removed'; attempt_3='cache_and_client_metadata_removed'; expected_http_status=200; expected_retry_count=2; expected_client_terminal_event='response.completed'; expected_billing_settlements=1 },
    [ordered]@{ name='created_event_is_visible_before_completion'; protocol='responses'; upstream_gate='completion_blocked'; expected_visible_events=@('response.created','response.output_text.delta'); forbidden_while_blocked='response.completed'; expected_final_event='response.completed' },
    [ordered]@{ name='failure_after_created_does_not_switch_supplier'; protocol='responses'; expected_upstream_attempts=1; expected_client_terminal_event='response.failed'; expected_billing_settlements=0 },
    [ordered]@{ name='responses_sse_event_field_is_preserved'; protocol='responses'; expected_event_fields=@('response.created','response.output_text.delta','response.completed','response.failed') }
) }
$groundTruth | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reference -Encoding UTF8
$fixture = Get-Content -Raw -LiteralPath $reference | ConvertFrom-Json
if ($fixture.cases.Count -ne 5 -or
    $fixture.cases[0].expected_log_status -ne 502 -or
    $fixture.cases[0].expected_retry_count -ne 0 -or
    $fixture.cases[0].expected_client_terminal_event -ne 'response.failed' -or
    $fixture.cases[0].expected_billing_settlements -ne 1 -or
    $fixture.cases[1].attempt_2 -ne "cache_fields_removed" -or
    $fixture.cases[1].attempt_3 -ne "cache_and_client_metadata_removed" -or
    $fixture.cases[1].expected_retry_count -ne 2 -or
    $fixture.cases[1].expected_billing_settlements -ne 1 -or
    ($fixture.cases[2].expected_visible_events -join ',') -ne 'response.created,response.output_text.delta' -or
    $fixture.cases[2].forbidden_while_blocked -ne 'response.completed' -or
    $fixture.cases[3].expected_upstream_attempts -ne 1 -or
    $fixture.cases[3].expected_billing_settlements -ne 0 -or
    ($fixture.cases[4].expected_event_fields -join ',') -ne 'response.created,response.output_text.delta,response.completed,response.failed') {
    throw "Responses midstream ground truth is invalid"
}

$selector = "OpenAiGatewayIntegrationTest#responsesStreamsCreatedEventBeforeUpstreamCompletion+responsesDisconnectAfterVisibleOutputFailsWithoutReplay+responsesFirstFailureEventRetriesBeforeCommit+responsesSingleRouteDisconnectRetriesBeforeCommit+responsesCompletedEventWinsOverPrematureTransportClose+responsesSingleRouteRetryEscapesCacheAndSessionAffinity+responsesFailureAfterCreatedEventDoesNotReplayOnAnotherRoute"
& (Join-Path $PSScriptRoot "test-responses-stream-resilience.ps1") `
    -LogName "responses-midstream-resilience" `
    -TestSelector $selector
if ($LASTEXITCODE -ne 0) {
    throw "Responses midstream resilience tests failed"
}
