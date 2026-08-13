package org.briarproject.mailbox.core.tor

import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType
import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.DEFAULT_OBFS4
import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.MEEK
import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4
import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.SNOWFLAKE
import org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.VANILLA
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tor spawns pluggable transports lazily and only where bridges are needed,
 * so nothing else exercises this.
 */
class CircumventionProviderImplTest {

    private val provider = CircumventionProviderImpl()

    private val bridgeCountries = (
        CircumventionProvider.COUNTRIES_DEFAULT_OBFS4 +
            CircumventionProvider.COUNTRIES_NON_DEFAULT_OBFS4 +
            CircumventionProvider.COUNTRIES_VANILLA +
            CircumventionProvider.COUNTRIES_MEEK +
            CircumventionProvider.COUNTRIES_SNOWFLAKE
        ).toSortedSet()

    @Test
    fun `every bridge type has a country-independent list`() {
        // getBridges() calls requireNonNull() on the zz fallback
        for (type in BridgeType.values()) {
            val bridges = provider.getBridges(type, "ZZ")
            assertTrue(bridges.isNotEmpty(), "no bridges-${type.letter}-zz")
        }
    }

    @Test
    fun `every country where bridges work resolves to real bridge lines`() {
        for (country in bridgeCountries) {
            assertTrue(provider.doBridgesWork(country), country)
            val types = provider.getSuitableBridgeTypes(country)
            assertTrue(types.isNotEmpty(), country)
            for (type in types) {
                val bridges = provider.getBridges(type, country)
                assertTrue(bridges.isNotEmpty(), "$country/$type")
            }
        }
    }

    @Test
    fun `every bridge line is a usable Bridge setting`() {
        for (country in bridgeCountries + "ZZ") {
            for (type in BridgeType.values()) {
                for (line in provider.getBridges(type, country)) {
                    assertTrue(line.startsWith("Bridge "), line)
                    val value = line.removePrefix("Bridge ")
                    assertTrue(value.isNotBlank(), "blank bridge in $country/$type")
                    assertFalse(value.startsWith("#"), "comment leaked: $line")
                }
            }
        }
    }

    @Test
    fun `comments and blank lines are skipped`() {
        // bridges-s-cn carries a comment above its bridge lines
        val cn = provider.getBridges(SNOWFLAKE, "CN")
        assertEquals(4, cn.size)
        assertTrue(cn.all { it.startsWith("Bridge snowflake ") }, cn.toString())
    }

    @Test
    fun `country-specific lists win over the fallback`() {
        val fallback = provider.getBridges(SNOWFLAKE, "ZZ")
        for (country in listOf("BY", "CN", "IR", "RU", "TM")) {
            assertNotEquals(fallback, provider.getBridges(SNOWFLAKE, country), country)
        }
        // No snowflake list of its own, so these fall back
        for (country in listOf("EG", "HK", "MM")) {
            assertContentEquals(fallback, provider.getBridges(SNOWFLAKE, country), country)
        }
    }

    /**
     * Why [AbstractTorPlugin] keys its "unchanged" check on the country too:
     * these countries ask for the same types but need different bridges.
     */
    @Test
    fun `countries sharing a type list still need different bridges`() {
        val sameTypes = listOf("BY", "CN", "EG", "HK", "IR", "MM", "RU")
        for (country in sameTypes) {
            assertEquals(
                listOf(NON_DEFAULT_OBFS4, SNOWFLAKE),
                provider.getSuitableBridgeTypes(country),
                country,
            )
        }
        val distinct = sameTypes.map { provider.getBridges(SNOWFLAKE, it) }.toSet()
        assertTrue(distinct.size > 1, "all countries returned identical snowflake bridges")
    }

    @Test
    fun `resource lookup is case-insensitive`() {
        assertContentEquals(
            provider.getBridges(SNOWFLAKE, "IR"),
            provider.getBridges(SNOWFLAKE, "ir"),
        )
    }

    @Test
    fun `turkmenistan is the only country that also gets meek`() {
        assertEquals(
            listOf(NON_DEFAULT_OBFS4, MEEK, SNOWFLAKE),
            provider.getSuitableBridgeTypes("TM"),
        )
        for (country in bridgeCountries - "TM") {
            assertFalse(provider.getSuitableBridgeTypes(country).contains(MEEK), country)
        }
    }

    @Test
    fun `countries with no recommendation get the defaults but are not offered bridges`() {
        for (country in listOf("GB", "PT", "US", "")) {
            assertFalse(provider.doBridgesWork(country), country)
            // Unreachable from the plugin, which checks doBridgesWork() first
            assertEquals(
                listOf(DEFAULT_OBFS4, VANILLA),
                provider.getSuitableBridgeTypes(country),
                country,
            )
        }
    }
}
