/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.app.preview;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import com.google.inject.util.Modules;
import io.cdap.cdap.api.security.store.SecureStore;
import io.cdap.cdap.app.guice.ProgramRunnerRuntimeModule;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.conf.SConfiguration;
import io.cdap.cdap.common.guice.ConfigModule;
import io.cdap.cdap.common.guice.IOModule;
import io.cdap.cdap.common.guice.LocalLocationModule;
import io.cdap.cdap.common.guice.preview.PreviewDiscoveryRuntimeModule;
import io.cdap.cdap.common.utils.Networks;
import io.cdap.cdap.config.guice.ConfigStoreModule;
import io.cdap.cdap.data.runtime.DataSetServiceModules;
import io.cdap.cdap.data.runtime.DataSetsModules;
import io.cdap.cdap.data.runtime.preview.PreviewDataModules;
import io.cdap.cdap.data2.dataset2.DatasetFramework;
import io.cdap.cdap.data2.dataset2.lib.table.leveldb.LevelDBTableService;
import io.cdap.cdap.data2.metadata.writer.MetadataServiceClient;
import io.cdap.cdap.data2.metadata.writer.NoOpMetadataServiceClient;
import io.cdap.cdap.internal.app.preview.PreviewRunnerService;
import io.cdap.cdap.internal.provision.ProvisionerModule;
import io.cdap.cdap.logging.guice.LocalLogAppenderModule;
import io.cdap.cdap.messaging.guice.MessagingServerRuntimeModule;
import io.cdap.cdap.metadata.MetadataReaderWriterModules;
import io.cdap.cdap.metrics.guice.MetricsClientRuntimeModule;
import io.cdap.cdap.security.auth.context.AuthenticationContextModules;
import io.cdap.cdap.security.guice.preview.PreviewSecureStoreModule;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.tephra.TransactionSystemClient;
import org.apache.twill.discovery.DiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Service for managing {@link PreviewRunnerService}.
 */
public class DefaultPreviewRunnerManager extends AbstractIdleService implements PreviewRunnerManager {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultPreviewRunnerManager.class);

  private final CConfiguration cConf;
  private final Configuration hConf;
  private final SConfiguration sConf;
  private final int maxConcurrentPreviews;
  private final DiscoveryService discoveryService;
  private final DatasetFramework datasetFramework;
  private final SecureStore secureStore;
  private final TransactionSystemClient transactionSystemClient;
  private final Path previewDataDir;
  private Injector previewInjector;
  private final PreviewRunnerModule previewRunnerModule;
  private final Set<Service> previewPollers;
  private final LevelDBTableService previewLevelDBTableService;

  @Inject
  DefaultPreviewRunnerManager(
    CConfiguration cConf, Configuration hConf,
    SConfiguration sConf, SecureStore secureStore, DiscoveryService discoveryService,
    @Named(DataSetsModules.BASE_DATASET_FRAMEWORK) DatasetFramework datasetFramework,
    TransactionSystemClient transactionSystemClient,
    @Named(PreviewConfigModule.PREVIEW_LEVEL_DB) LevelDBTableService previewLevelDBTableService,
    PreviewRunnerModule previewRunnerModule) {
    this.cConf = cConf;
    this.hConf = hConf;
    this.sConf = sConf;
    this.datasetFramework = datasetFramework;
    this.secureStore = secureStore;
    this.discoveryService = discoveryService;
    this.transactionSystemClient = transactionSystemClient;
    this.previewDataDir = Paths.get(cConf.get(Constants.CFG_LOCAL_DATA_DIR), "preview").toAbsolutePath();
    this.maxConcurrentPreviews = cConf.getInt(Constants.Preview.CACHE_SIZE, 10);
    this.previewPollers = new HashSet<>();
    this.previewRunnerModule = previewRunnerModule;
    this.previewLevelDBTableService = previewLevelDBTableService;
  }

  @Override
  protected synchronized void startUp() throws Exception {
    previewInjector = createPreviewInjector();
    // Create and start the preview poller services.
    for (int i = 0; i < maxConcurrentPreviews; i++) {
      Service pollerService = previewInjector.getInstance(PreviewRunnerService.class);
      pollerService.startAndWait();
      previewPollers.add(pollerService);
    }
    PreviewRunner runner = previewInjector.getInstance(PreviewRunner.class);
    if (runner instanceof Service) {
      ((Service) runner).startAndWait();
    }
  }

  @Override
  protected synchronized void shutDown() throws Exception {
    PreviewRunner runner = previewInjector.getInstance(PreviewRunner.class);
    if (runner instanceof Service) {
      stopQuietly((Service) runner);
    }

    for (Service pollerService : previewPollers) {
      stopQuietly(pollerService);
    }
  }

  private void stopQuietly(Service service) {
    try {
      service.stopAndWait();
    } catch (Exception e) {
      LOG.debug("Error stopping the preview runner.", e);
    }
  }

  /**
   * Create injector for the given application id.
   */
  @VisibleForTesting
  Injector createPreviewInjector() throws IOException {
    CConfiguration previewCConf = CConfiguration.copy(cConf);

    // Change all services bind address to local host
    String localhost = InetAddress.getLoopbackAddress().getHostName();
    StreamSupport.stream(previewCConf.spliterator(), false)
      .map(Map.Entry::getKey)
      .filter(s -> s.endsWith(".bind.address"))
      .forEach(key -> previewCConf.set(key, localhost));

    Path previewDir = Files.createDirectories(previewDataDir);

    previewCConf.set(Constants.CFG_LOCAL_DATA_DIR, previewDir.toString());
    previewCConf.setIfUnset(Constants.CFG_DATA_LEVELDB_DIR, previewDir.toString());
    previewCConf.setBoolean(Constants.Explore.EXPLORE_ENABLED, false);
    // Use No-SQL store for preview data
    previewCConf.set(Constants.Dataset.DATA_STORAGE_IMPLEMENTATION, Constants.Dataset.DATA_STORAGE_NOSQL);

    // Setup Hadoop configuration
    Configuration previewHConf = new Configuration(hConf);
    previewHConf.set(MRConfig.FRAMEWORK_NAME, MRConfig.LOCAL_FRAMEWORK_NAME);
    previewHConf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                     previewDir.resolve("fs").toUri().toString());

    SConfiguration previewSConf = SConfiguration.copy(sConf);
    return Guice.createInjector(
      new ConfigModule(previewCConf, previewHConf, previewSConf),
      new IOModule(),
      new AuthenticationContextModules().getMasterModule(),
      new PreviewSecureStoreModule(secureStore),
      new PreviewDiscoveryRuntimeModule(discoveryService),
      new LocalLocationModule(),
      new ConfigStoreModule(),
      previewRunnerModule,
      new ProgramRunnerRuntimeModule().getStandaloneModules(),
      new PreviewDataModules().getDataFabricModule(transactionSystemClient, previewLevelDBTableService),
      new PreviewDataModules().getDataSetsModule(datasetFramework),
      new DataSetServiceModules().getStandaloneModules(),
      // Use the in-memory module for metrics collection, which metrics still get persisted to dataset, but
      // save threads for reading metrics from TMS, as there won't be metrics in TMS.
      new MetricsClientRuntimeModule().getInMemoryModules(),
      new LocalLogAppenderModule(),
      new MessagingServerRuntimeModule().getInMemoryModules(),
      Modules.override(new MetadataReaderWriterModules().getInMemoryModules()).with(new AbstractModule() {
        @Override
        protected void configure() {
          // we don't start a metadata service in preview, so don't attempt to create any metadata
          bind(MetadataServiceClient.class).to(NoOpMetadataServiceClient.class);
        }
      }),
      new ProvisionerModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
        }

        @Provides
        @Named(Constants.Service.MASTER_SERVICES_BIND_ADDRESS)
        @SuppressWarnings("unused")
        public InetAddress providesHostname(CConfiguration cConf) {
          String address = cConf.get(Constants.Preview.ADDRESS);
          return Networks.resolve(address, new InetSocketAddress("localhost", 0).getAddress());
        }
      }
    );
  }
}
