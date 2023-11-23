package com.android.tools.metalava

import com.android.tools.metalava.model.PackageItem
import java.io.File

/**
 *
 * We see a number of different styles:
 * - exact match (foo)
 * - prefix match (foo*, probably not intentional)
 * - subpackage match (foo.*)
 * - package and subpackage match (foo:foo.*)
 *
 * Real examples:
 * args: "-stubpackages com.android.test.power ",
 * args: "-stubpackages android.car* ",
 * args: "-stubpackages com.android.ahat:com.android.ahat.*",
 *
 * Note that doclava does *not* include subpackages by default: -stubpackage foo
 * will match only foo, not foo.bar. Note also that "foo.*" will not match "foo",
 * so doclava required you to supply both: "foo:foo.*".
 *
 * In metalava we've changed that: it's not likely that you want to
 * match any subpackage of foo but not foo itself, so foo.* is taken
 * to mean "foo" and "foo.*".
 */
class PackageFilter(
    private val exactPackages: MutableSet<String> = mutableSetOf(),
    private val packagePrefixes: MutableList<String> = mutableListOf()
) {
    init {
        // Wildcards should have been removed by option
        assert(packagePrefixes.none { it.contains("*") })
    }

    fun matches(qualifiedName: String): Boolean {
        if (exactPackages.contains(qualifiedName)) {
            return true
        }

        if (packagePrefixes.isNotEmpty()) {
            for (prefix in packagePrefixes) {
                if (qualifiedName.startsWith(prefix)) {
                    return true
                }
            }
        }

        return false
    }

    fun addPackages(path: String) {
        for (pkg in path.split(File.pathSeparatorChar)) {
            val index = pkg.indexOf('*')
            if (index != -1) {
                if (index < pkg.length - 1) {
                    throw DriverException(stderr = "Wildcards in stub packages must be at the end of the package: $pkg)")
                }
                val prefix = pkg.removeSuffix("*")
                if (prefix.endsWith(".")) {
                    // In doclava, "foo.*" does not match "foo", but we want to do that.
                    exactPackages.add(prefix.substring(0, prefix.length - 1))
                }
                packagePrefixes += prefix
            } else {
                exactPackages.add(pkg)
            }
        }
    }

    fun matches(packageItem: PackageItem): Boolean {
        return matches(packageItem.qualifiedName())
    }
}