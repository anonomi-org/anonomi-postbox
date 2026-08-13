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

package org.briarproject.mailbox.core.tor

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.briarproject.mailbox.core.event.EventBus
import org.briarproject.mailbox.core.event.EventExecutor
import org.briarproject.mailbox.core.files.FileProvider
import org.briarproject.mailbox.core.lifecycle.IoExecutor
import org.briarproject.mailbox.core.lifecycle.LifecycleManager
import org.briarproject.mailbox.core.server.WebServerManager
import org.briarproject.mailbox.core.settings.SettingsManager
import org.briarproject.mailbox.core.tor.TorConstants.CONTROL_PORT
import org.briarproject.mailbox.core.tor.TorConstants.SOCKS_PORT
import org.briarproject.mailbox.core.util.OsUtils.isLinux
import org.briarproject.onionwrapper.CircumventionProvider
import org.briarproject.onionwrapper.JavaLocationUtilsFactory.createJavaLocationUtils
import org.briarproject.onionwrapper.LocationUtils
import org.briarproject.onionwrapper.UnixTorWrapper
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class JavaTorModule {

    @Provides
    @Singleton
    fun provideJavaTorPlugin(
        @IoExecutor ioExecutor: Executor,
        @EventExecutor eventExecutor: Executor,
        settingsManager: SettingsManager,
        networkManager: NetworkManager,
        locationUtils: LocationUtils,
        circumventionProvider: CircumventionProvider,
        lifecycleManager: LifecycleManager,
        eventBus: EventBus,
        fileProvider: FileProvider,
        webServerManager: WebServerManager,
    ): TorPlugin {
        val torDir = File(fileProvider.root, "tor")
        val architecture = this.architecture
        val tor = UnixTorWrapper(
            ioExecutor,
            eventExecutor,
            architecture.orEmpty(),
            torDir,
            SOCKS_PORT,
            CONTROL_PORT
        )
        return TorPluginImpl(
            ioExecutor,
            settingsManager,
            networkManager,
            locationUtils,
            circumventionProvider,
            tor,
            architecture
        ) { webServerManager.port }.also {
            lifecycleManager.registerService(it)
            eventBus.addListener(it)
        }
    }

    /**
     * The directory the Tor and lyrebird binaries are unpacked into, which is
     * also how onionwrapper addresses them on the classpath. These are the
     * names tor-linux and lyrebird-linux ship, not the ones Java reports.
     */
    private val architecture: String?
        get() {
            if (isLinux()) {
                when (System.getProperty("os.arch")) {
                    "amd64" -> return "x86_64"
                    "aarch64" -> return "aarch64"
                    "arm" -> return "armhf"
                }
            }
            // Tor is not supported on this architecture
            return null
        }

    @Provides
    @Singleton
    fun provideNetworkManager(networkManager: MailboxLibNetworkManager): NetworkManager {
        return networkManager
    }

    @Provides
    @Singleton
    fun provideLocationUtils(): LocationUtils = createJavaLocationUtils()

}
