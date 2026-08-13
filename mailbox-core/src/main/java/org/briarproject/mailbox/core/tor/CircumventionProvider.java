/*
 *     Briar Mailbox
 *     Copyright (C) 2021-2022  The Briar Project
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.briarproject.mailbox.core.tor;

import org.briarproject.mailbox.core.lifecycle.IoExecutor;

import java.util.List;

public interface CircumventionProvider {

	enum BridgeType {
		DEFAULT_OBFS4("d"),
		NON_DEFAULT_OBFS4("n"),
		VANILLA("v"),
		MEEK("m"),
		SNOWFLAKE("s");

		/**
		 * The letter identifying this type's bridge resource files, which are
		 * named {@code bridges-<letter>-<country code>}.
		 */
		final String letter;

		BridgeType(String letter) {
			this.letter = letter;
		}
	}

	/**
	 * Countries where default (publicly listed) obfs4 bridges are likely to
	 * work. Empty upstream: listed bridges are the first thing censors block.
	 * While it stays empty nothing selects this type, as the fallback in
	 * {@link #getSuitableBridgeTypes(String)} runs only for countries that
	 * {@link #doBridgesWork(String)} rejects.
	 */
	String[] COUNTRIES_DEFAULT_OBFS4 = {};

	/**
	 * Countries where non-default (unlisted) obfs4 bridges are likely to work.
	 */
	String[] COUNTRIES_NON_DEFAULT_OBFS4 =
			{"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	/**
	 * Countries where vanilla bridges are likely to work. Empty upstream:
	 * vanilla bridges are blocked by DPI wherever bridges are needed at all.
	 */
	String[] COUNTRIES_VANILLA = {};

	/**
	 * Countries where meek is likely to work.
	 */
	String[] COUNTRIES_MEEK = {"TM"};

	/**
	 * Countries where snowflake is likely to work.
	 */
	String[] COUNTRIES_SNOWFLAKE =
			{"BY", "CN", "EG", "HK", "IR", "MM", "RU", "TM"};

	/**
	 * Returns true if bridge connections of some type work in the given
	 * country.
	 */
	boolean doBridgesWork(String countryCode);

	/**
	 * Returns every type of bridge connection that is suitable for the given
	 * country, or default obfs4 and vanilla bridges if no bridge type is known
	 * to work there.
	 */
	List<BridgeType> getSuitableBridgeTypes(String countryCode);

	/**
	 * Returns the bridge lines of the given type for the given country,
	 * falling back to the country-independent list if there are no bridges
	 * specific to that country.
	 */
	@IoExecutor
	List<String> getBridges(BridgeType type, String countryCode);

}
