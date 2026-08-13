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

import org.briarproject.mailbox.core.PoliteExecutor;
import org.briarproject.mailbox.core.db.DbException;
import org.briarproject.mailbox.core.event.Event;
import org.briarproject.mailbox.core.event.EventListener;
import org.briarproject.mailbox.core.lifecycle.IoExecutor;
import org.briarproject.mailbox.core.lifecycle.ServiceException;
import org.briarproject.mailbox.core.settings.Settings;
import org.briarproject.mailbox.core.settings.SettingsManager;
import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.CircumventionProvider.BridgeType;
import org.briarproject.onionwrapper.LocationUtils;
import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.logging.Level.INFO;
import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;
import static org.briarproject.mailbox.core.tor.TorConstants.HS_ADDRESS_V3;
import static org.briarproject.mailbox.core.tor.TorConstants.HS_PRIVATE_KEY_V3;
import static org.briarproject.mailbox.core.tor.TorConstants.SETTINGS_NAMESPACE;
import static org.briarproject.onionwrapper.CircumventionProvider.BridgeType.MEEK;
import static org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTING;
import static org.briarproject.onionwrapper.TorWrapper.TorState.DISABLED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.NOT_STARTED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.STARTED;

/**
 * Drives a {@link TorWrapper}: decides when the network and bridges should be
 * turned on, publishes the onion service, and turns the wrapper's state into
 * the {@link TorState} the app shows.
 */
@ThreadSafe
public class TorPluginImpl implements TorPlugin, TorWrapper.Observer,
		EventListener {

	/**
	 * The port clients see, i.e. the second argument of
	 * {@link TorWrapper#publishHiddenService(int, int, String)}. Swapping it
	 * with the local port silently moves the service.
	 */
	private static final int HS_REMOTE_PORT = 80;

	/**
	 * The number of uploads of our onion service descriptor we wait for
	 * before we consider our onion service to be published.
	 * In reality, the actual reachability is more complicated,
	 * but this might be a reasonable heuristic.
	 */
	private static final int HS_DESC_UPLOADS = 1;

	/**
	 * onionwrapper puts Tor's control port messages on java.util.logging,
	 * which reaches logcat. Detach its loggers from the handlers they inherit
	 * rather than raising their level, and pin the level to INFO so nothing
	 * else can raise it: onionwrapper only reports a descriptor upload while
	 * INFO is loggable, and that report is what makes us
	 * {@link TorState.Published}. Held in a field because the log manager
	 * keeps only a weak reference.
	 */
	@SuppressWarnings("unused")
	private static final Logger ONIONWRAPPER_LOG = detachOnionwrapperLog();

	private final Executor ioExecutor;
	private final Executor connectionStatusExecutor;
	private final SettingsManager settingsManager;
	private final NetworkManager networkManager;
	private final LocationUtils locationUtils;
	private final CircumventionProvider circumventionProvider;
	private final TorWrapper tor;
	@Nullable
	private final String architecture;
	private final IntSupplier portSupplier;
	private final AtomicBoolean used = new AtomicBoolean(false);

	private final PluginState state = new PluginState();

	public TorPluginImpl(Executor ioExecutor,
			SettingsManager settingsManager,
			NetworkManager networkManager,
			LocationUtils locationUtils,
			CircumventionProvider circumventionProvider,
			TorWrapper tor,
			@Nullable String architecture,
			IntSupplier portSupplier) {
		this.ioExecutor = ioExecutor;
		this.settingsManager = settingsManager;
		this.networkManager = networkManager;
		this.locationUtils = locationUtils;
		this.circumventionProvider = circumventionProvider;
		this.tor = tor;
		this.architecture = architecture;
		this.portSupplier = portSupplier;
		// Don't execute more than one connection status check at a time
		connectionStatusExecutor =
				new PoliteExecutor("TorPlugin", ioExecutor, 1);
	}

	private static Logger detachOnionwrapperLog() {
		Logger log = Logger.getLogger("org.briarproject.onionwrapper");
		log.setUseParentHandlers(false);
		log.setLevel(INFO);
		return log;
	}

	@Override
	public StateFlow<TorState> getState() {
		return state.state;
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		// We have no Tor binary for this device
		if (architecture == null) throw new ServiceException();
		tor.setObserver(this);
		try {
			tor.start();
		} catch (IOException e) {
			throw new ServiceException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ServiceException(e);
		}
		// Check whether we're online
		updateConnectionStatus(networkManager.getNetworkStatus());
		// Create an onion service if necessary
		ioExecutor.execute(this::publishHiddenService);
	}

	@Override
	public void stopService() {
		try {
			tor.stop();
		} catch (IOException e) {
			// Nothing we can do about it at this point
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@IoExecutor
	private void publishHiddenService() {
		if (!tor.isTorRunning()) return;
		int port;
		try {
			port = portSupplier.getAsInt();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		Settings s;
		try {
			s = settingsManager.getSettings(SETTINGS_NAMESPACE);
		} catch (DbException e) {
			s = new Settings();
		}
		createV3HiddenService(port, s.get(HS_PRIVATE_KEY_V3));
	}

	@IoExecutor
	private void createV3HiddenService(int port, @Nullable String privKey) {
		HiddenServiceProperties props;
		try {
			props = tor.publishHiddenService(port, HS_REMOTE_PORT, privKey);
		} catch (IOException e) {
			return;
		}
		// The address is only stored the first time round, along with the key
		// it was derived from
		if (privKey != null) return;
		Settings s = new Settings();
		s.put(HS_ADDRESS_V3, props.onion);
		s.put(HS_PRIVATE_KEY_V3, props.privKey);
		try {
			settingsManager.mergeSettings(s, SETTINGS_NAMESPACE);
		} catch (DbException e) {
			// The service is published but we won't be able to reuse its key
		}
	}

	@Override
	@Nullable
	public String getHiddenServiceAddress() throws DbException {
		Settings s = settingsManager.getSettings(SETTINGS_NAMESPACE);
		return s.get(HS_ADDRESS_V3);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof NetworkStatusEvent) {
			updateConnectionStatus(((NetworkStatusEvent) e).getStatus());
		}
	}

	private void updateConnectionStatus(NetworkStatus status) {
		connectionStatusExecutor.execute(() -> {
			if (!tor.isTorRunning()) return;
			boolean online = status.isConnected();
			boolean wifi = status.isWifi();
			boolean ipv6Only = status.isIpv6Only();
			String country = locationUtils.getCurrentCountry();
			boolean bridgesWork =
					circumventionProvider.shouldUseBridges(country);

			boolean enableNetwork = false, enableConnectionPadding = false;
			List<BridgeType> bridgeTypes = emptyList();

			if (online) {
				enableNetwork = true;
				if (bridgesWork) {
					bridgeTypes = ipv6Only ? singletonList(MEEK)
							: circumventionProvider
									.getSuitableBridgeTypes(country);
				}
				enableConnectionPadding = wifi;
			}

			try {
				if (enableNetwork) {
					enableBridges(bridgeTypes, country);
					tor.enableConnectionPadding(enableConnectionPadding);
					tor.enableIpv6(ipv6Only);
				}
				tor.enableNetwork(enableNetwork);
			} catch (IOException e) {
				// We'll try again on the next network status event
			}
		});
	}

	/**
	 * The bridges for a type differ per country, so the same types elsewhere
	 * are different bridges. The wrapper compares the bridge lines themselves
	 * before reconfiguring Tor, so a check on the types alone is both
	 * redundant and wrong.
	 */
	private void enableBridges(List<BridgeType> bridgeTypes, String country)
			throws IOException {
		List<String> bridges = new ArrayList<>();
		for (BridgeType bridgeType : bridgeTypes) {
			bridges.addAll(circumventionProvider
					.getBridges(bridgeType, country));
		}
		// Branch on the lines, not the types: the wrapper rejects an empty
		// list with an unchecked exception, and types we have no bridges for
		// resolve to one
		if (bridges.isEmpty()) tor.disableBridges();
		else tor.enableBridges(bridges);
	}

	@Override
	public void onState(TorWrapper.TorState s) {
		state.onWrapperStateChanged(s);
	}

	@Override
	public void onBootstrapPercentage(int percentage) {
		state.setBootstrapPercentage(percentage);
	}

	@Override
	public void onHsDescriptorUpload(String onion) {
		state.onServiceDescriptorUploaded();
	}

	@Override
	public void onClockSkewDetected(long skewSeconds) {
		state.setClockSkewed();
	}

	@ThreadSafe
	private static class PluginState {

		private final MutableStateFlow<TorState> state =
				MutableStateFlow(TorState.StartingStopping.INSTANCE);

		@GuardedBy("this")
		private TorWrapper.TorState wrapperState = NOT_STARTED;

		@GuardedBy("this")
		private boolean clockSkewed = false;

		@GuardedBy("this")
		private int bootstrapPercentage = 0, numServiceUploads = 0;

		synchronized void onWrapperStateChanged(TorWrapper.TorState s) {
			wrapperState = s;
			// Reaching the network settles the question of the clock
			if (s == CONNECTED) clockSkewed = false;
			state.setValue(getCurrentState());
		}

		synchronized void setBootstrapPercentage(int percentage) {
			if (percentage < 0 || percentage > 100) {
				throw new IllegalArgumentException(
						"percentage: " + percentage);
			}
			bootstrapPercentage = percentage;
			if (percentage == 100) clockSkewed = false;
			state.setValue(getCurrentState());
		}

		synchronized void setClockSkewed() {
			clockSkewed = true;
			state.setValue(getCurrentState());
		}

		synchronized void onServiceDescriptorUploaded() {
			numServiceUploads++;
			state.setValue(getCurrentState());
		}

		/**
		 * A skewed clock is only worth reporting while it is what's keeping
		 * us off the network. Tor never says the skew is gone, so reporting
		 * it once connected would leave the advice on screen with nothing to
		 * clear it.
		 */
		@GuardedBy("this")
		private TorState getCurrentState() {
			if (wrapperState == STARTED) {
				return new TorState.Enabling(bootstrapPercentage);
			}
			if (wrapperState == DISABLED) return TorState.Inactive.INSTANCE;
			if (wrapperState == CONNECTED) {
				return numServiceUploads >= HS_DESC_UPLOADS ?
						TorState.Published.INSTANCE :
						TorState.Active.INSTANCE;
			}
			if (wrapperState == CONNECTING) {
				return clockSkewed ? TorState.ClockSkewed.INSTANCE :
						new TorState.Enabling(bootstrapPercentage);
			}
			// NOT_STARTED, STARTING, STOPPING or STOPPED
			return TorState.StartingStopping.INSTANCE;
		}
	}
}
