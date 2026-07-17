// Local build-environment fix only: old 1.21.x adapters' pinned codebook cannot parse
// Java 25 class files (major 69). Run their toolchain on 21; 26.x adapters stay on 25.
// afterEvaluate so it wins over buildlogic.common's toolchain(25).
val oldAdapters = setOf(
    "adapter-1_21", "adapter-1_21_4", "adapter-1_21_5",
    "adapter-1_21_6", "adapter-1_21_9", "adapter-1_21_11",
)

allprojects {
    if (name in oldAdapters) {
        afterEvaluate {
            extensions.findByType(JavaPluginExtension::class.java)?.toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}
