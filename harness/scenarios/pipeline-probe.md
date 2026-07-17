# W0.2 detached-prepare -> region-commit pipeline probe.
#
# scenario.sh runs the two named phases in separate Folia processes. The initial phase saves
# the safety section; the readback phase proves the section survived a real restart.

@phase initial
fawe-harness environment
@waitfor FAWE_HARNESS_ENV_OK 60
@waitfor FAWE_HARNESS_ASYNC_CHUNK_OK 120

fawe-harness safety
@waitfor FAWE_HARNESS_PIPELINE_SAFETY_OK 180

fawe-harness tournament per-chunk 16
@waitfor FAWE_HARNESS_TOURNAMENT_OK strategy=per-chunk 300
fawe-harness tournament batched-neighbor 16
@waitfor FAWE_HARNESS_TOURNAMENT_OK strategy=batched-neighbor 300
fawe-harness tournament region-sweep 16
@waitfor FAWE_HARNESS_TOURNAMENT_OK strategy=region-sweep 300

@bots start 1
@waitfor pipeline_01 joined the game 120
execute in minecraft:overworld run tp pipeline_01 4096 82 4096
@sleep 3
fawe-harness hazard-packet pipeline_01 96 96
@waitfor FAWE_HARNESS_PACKET_OK player=pipeline_01 120
@waitfor BOT-CHAT pipeline_01 FAWE_PACKET_DELIVERED 120
@bots stop

fawe-harness hazard-neighbor suppressed
@waitfor FAWE_HARNESS_NEIGHBOR_OK mode=suppressed 120
fawe-harness hazard-neighbor normal
@waitfor FAWE_HARNESS_NEIGHBOR_OK mode=normal 180

fawe-harness lighting
@waitfor FAWE_HARNESS_LIGHTING_OK 300

fawe-harness scratch-world
@waitfor FAWE_HARNESS_SCRATCH_RESULT 120

save-all flush
@sleep 5
@endphase

@phase readback
fawe-harness verify-readback
@waitfor FAWE_HARNESS_PIPELINE_READBACK_OK 180
@endphase
