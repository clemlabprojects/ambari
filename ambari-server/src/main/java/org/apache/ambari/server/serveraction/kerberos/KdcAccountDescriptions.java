/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.serveraction.kerberos;

/**
 * A KDC whose accounts carry a description (Active Directory, FreeIPA users): KDPS writes there who created an
 * account, which is how Ambari tells the accounts whose key it may reset from those someone else uses.
 */
public interface KdcAccountDescriptions {

  /**
   * @return the description of the principal's account; null when it has none, does not exist, or the KDC keeps no
   *         description for that kind of principal
   */
  String getAccountDescription(String principal) throws KerberosOperationException;

  /** Sets the description of an existing principal's account. */
  void setAccountDescription(String principal, String description) throws KerberosOperationException;
}
