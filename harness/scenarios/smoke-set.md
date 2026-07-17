# smoke-set

# Console-reachable W0.1 smoke. The harness probe receives this command on the global
# console thread, dispatches through Folia's RegionScheduler, replaces a 4x4x4 volume on
# its owning region thread, and reads all 64 blocks back on that same thread.
#
# With FAWE installed, `fawe` additionally exercises the console diagnostic entry point.
# The actual `//pos1`, `//pos2`, `//set stone` player path requires an actor driver; the
# SS2 bot is not self-contained, so that step is deliberately deferred to W0.2.

@with-plugin fawe
fawe-harness fill-verify
@waitfor FAWE_HARNESS_REGION_OWNER_OK owner=true 30
@waitfor FAWE_HARNESS_VOLUME_OK blocks=64 material=STONE owner=true 30
