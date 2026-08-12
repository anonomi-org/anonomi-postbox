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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Arrays.asList;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.DEFAULT_OBFS4;
import static org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.MEEK;
import static org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.NON_DEFAULT_OBFS4;
import static org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.SNOWFLAKE;
import static org.briarproject.mailbox.core.tor.CircumventionProvider.BridgeType.VANILLA;

@Immutable
class CircumventionProviderImpl implements CircumventionProvider {

	/**
	 * Country code of the bridge lists used when we have nothing specific to
	 * the user's country.
	 */
	private final static String DEFAULT_COUNTRY_CODE = "ZZ";

	private static final Set<String> USE_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_DEFAULT_OBFS4));
	private static final Set<String> USE_NON_DEFAULT_OBFS4 =
			new HashSet<>(asList(COUNTRIES_NON_DEFAULT_OBFS4));
	private static final Set<String> USE_VANILLA =
			new HashSet<>(asList(COUNTRIES_VANILLA));
	private static final Set<String> USE_MEEK =
			new HashSet<>(asList(COUNTRIES_MEEK));
	private static final Set<String> USE_SNOWFLAKE =
			new HashSet<>(asList(COUNTRIES_SNOWFLAKE));

	@Inject
	CircumventionProviderImpl() {
	}

	@Override
	public boolean doBridgesWork(String countryCode) {
		return USE_DEFAULT_OBFS4.contains(countryCode) ||
				USE_NON_DEFAULT_OBFS4.contains(countryCode) ||
				USE_VANILLA.contains(countryCode) ||
				USE_MEEK.contains(countryCode) ||
				USE_SNOWFLAKE.contains(countryCode);
	}

	@Override
	public List<BridgeType> getSuitableBridgeTypes(String countryCode) {
		List<BridgeType> types = new ArrayList<>();
		if (USE_DEFAULT_OBFS4.contains(countryCode)) types.add(DEFAULT_OBFS4);
		if (USE_NON_DEFAULT_OBFS4.contains(countryCode)) {
			types.add(NON_DEFAULT_OBFS4);
		}
		if (USE_VANILLA.contains(countryCode)) types.add(VANILLA);
		if (USE_MEEK.contains(countryCode)) types.add(MEEK);
		if (USE_SNOWFLAKE.contains(countryCode)) types.add(SNOWFLAKE);
		// If we have no recommendation for this country, use the defaults
		if (types.isEmpty()) {
			types.add(DEFAULT_OBFS4);
			types.add(VANILLA);
		}
		return types;
	}

	@Override
	@IoExecutor
	public List<String> getBridges(BridgeType type, String countryCode) {
		ClassLoader cl = getClass().getClassLoader();
		// Try to load bridges that are specific to this country code
		InputStream is =
				cl.getResourceAsStream(resourceName(type, countryCode));
		if (is == null) {
			// Nothing for this country, fall back to the generic list
			is = requireNonNull(cl.getResourceAsStream(
					resourceName(type, DEFAULT_COUNTRY_CODE)));
		}
		List<String> bridges = new ArrayList<>();
		Scanner scanner = new Scanner(is);
		while (scanner.hasNextLine()) {
			bridges.add("Bridge " + scanner.nextLine());
		}
		scanner.close();
		return bridges;
	}

	private String resourceName(BridgeType type, String countryCode) {
		return "bridges-" + type.letter + "-" + countryCode.toLowerCase(US);
	}

}
