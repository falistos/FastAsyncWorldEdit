# Harness-only measurement path. It exercises detached preparation followed by
# owner-region commits; it does not claim to measure the FAWE EditSession path.
@waitfor Done \(|For help, type "help" 240
@mark op:probe:begin
fawe-harness perf-run probe-region-sweep region-sweep 16
@waitfor FAWE_PROBE_PERF op=probe-region-sweep .*result=ok 180
fawe-harness perf-queue probe-region-sweep
@waitfor FAWE_PROBE_QUEUE op=probe-region-sweep 30
@mark op:probe:end
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
