// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.util.logging.Level;

import org.junit.jupiter.api.Assertions;

public class WalletPasswordSecret extends Secret {
  
  private String walletPassword;
  
  /**
   * Construct WalletPassword secret.
   * 
   * @param namespace namespace that this secret is in
   * @param secretName secret name
   * @param walletPassword wallet password
   * @throws Exception on failure
   */
  public WalletPasswordSecret(String namespace, String secretName, String walletPassword)throws Exception {
    this.namespace = namespace;
    this.secretName = secretName;
    this.walletPassword = walletPassword;
    

    // delete the secret first if exists
    deleteSecret();

    // create the secret
    String command =
        "kubectl -n "
            + this.namespace
        + " create secret generic "
        + this.secretName
        + " --from-literal=walletPassword="
        + this.walletPassword;
            
    LoggerHelper.getLocal().log(Level.INFO, "Running " + command);
    try {
      ExecResult result = TestUtils.exec(command);
      LoggerHelper.getLocal().log(Level.INFO, "command result " + result.stdout().trim());
    } catch (Exception ex) {
      ex.printStackTrace();
      Assertions.fail("Failed to create walletPasswordSecret.\n", ex.getCause());
    } 
  }
  
  public String getwalletPassword() {
    return walletPassword;
  }
  
  private void deleteSecret() throws Exception {
    String command = "kubectl -n " + namespace + " delete secret " + secretName;
    LoggerHelper.getLocal().log(Level.INFO, "Running " + command);
    try {
      ExecCommand.exec(command);
    } catch (Exception ex) {
      ex.printStackTrace();
      Assertions.fail("Failed to excute command.\n", ex.getCause());
    } 
  }

}
