package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;

/** Public door to the throwaway-keyring store of {@link JobListenerTest}, for other packages. */
public final class JobListenerTestAssets {

  private JobListenerTestAssets() {}

  public static EncryptedAssetService assets() {
    return JobListenerTest.testAssets();
  }
}
