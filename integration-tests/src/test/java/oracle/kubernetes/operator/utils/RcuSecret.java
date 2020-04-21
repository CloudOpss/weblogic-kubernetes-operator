// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.util.logging.Level;

public class RcuSecret extends Secret {

  private String sysUsername;
  private String sysPassword;
  private String rcuPrefix;
  private String rcuSchemaPass;
  private String rcuConnection;
  

  /**
   * Construct RCU secret.
   * 
   * @param namespace namespace
   * @param secretName secret name
   * @param username username
   * @param password password
   * @param sysUsername sys username
   * @param sysPassword sys password
   * @throws Exception on failure
   */
  public RcuSecret(
      String namespace,
      String secretName,
      String username,
      String password,
      String sysUsername,
      String sysPassword)
      throws Exception {
    this.namespace = namespace;
    this.secretName = secretName;
    this.username = username;
    this.password = password;
    this.sysUsername = sysUsername;
    this.sysPassword = sysPassword;

    // delete the secret first if exists
    deleteSecret();

    // create the secret
    String command =
        "kubectl -n "
            + this.namespace
            + " create secret generic "
            + this.secretName
            + " --from-literal=username="
            + this.username
            + " --from-literal=password="
            + this.password
            + " --from-literal=sys_username="
            + this.sysUsername
            + " --from-literal=sys_password="
            + this.sysPassword;
    LoggerHelper.getLocal().log(Level.INFO, "Running " + command);
    ExecResult result = TestUtils.exec(command);
    LoggerHelper.getLocal().log(Level.INFO, "command result " + result.stdout().trim());
  }
  
  /**
   * Construct RCU secret.
   * 
   * @param secretname secret name
   * @param rcuPrefix username
   * @param password password
   * @param sysUsername sys username
   * @param sysPassword sys password
   * @throws Exception on failure
   */
  public RcuSecret(
      String namespace,
      String secretName,
      String rcuPrefix,
      String rcuSchemaPass,
      String rcuConnection
  )
      throws Exception {
    this.namespace = namespace;
    this.secretName = secretName;
    this.rcuPrefix = rcuPrefix;
    this.rcuSchemaPass = rcuSchemaPass;
    this.rcuConnection = rcuConnection;
    
    // delete the secret first if exists
    deleteSecret();

    // create the secret
    String command =
        "kubectl -n "
            + this.namespace
            + " create secret generic "
            + this.secretName
            + " --from-literal=rcu_prefix="
            + this.rcuPrefix
            + " --from-literal=rcu_schema_password="
            + this.rcuSchemaPass
            + " --from-literal=rcu_db_conn_string="
            + this.rcuConnection;
            
    LoggerHelper.getLocal().log(Level.INFO, "Running " + command);
    
    try {
      ExecResult result = TestUtils.exec(command);
      LoggerHelper.getLocal().log(Level.INFO, "command result " + result.stdout().trim());
    } catch (Exception ex) {
      ex.getCause();
    }     
  }
  
  /**
   * Get system username
   * 
   * @return sysUsername system username
   */
  public String getSysUsername() {
    return sysUsername;
  }
  
  /**
   * Get system password
   * 
   * @return sysPassword system password
   */
  public String getSysPassword() {
    return sysPassword;
  }
  
  /**
   * Get RCU prefix
   * 
   * @return rcuPrefix RCU prefix 
   */
  public String getrcuPrefix() {
    return rcuPrefix;
  }
  
  /**
   * Get RCU schema passwordd
   * 
   * @return rcuSchemaPass RCU schema password 
   */
  public String getrcuSchemaPass() {
    return rcuSchemaPass;
  }
  
  /**
   * Get RCU connection
   * 
   * @return rcuConnection RCU connection 
   */
  public String getrcuConnection() {
    return rcuConnection;
  }

  private void deleteSecret() throws Exception {
    String command = "kubectl -n " + namespace + " delete secret " + secretName;
    LoggerHelper.getLocal().log(Level.INFO, "Running " + command);
    ExecCommand.exec(command);
  }

}
