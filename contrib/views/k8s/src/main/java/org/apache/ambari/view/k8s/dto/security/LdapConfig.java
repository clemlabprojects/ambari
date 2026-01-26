package org.apache.ambari.view.k8s.dto.security;

/**
 * LDAP / AD configuration.
 */
public class LdapConfig {
    public String url;
    public String bindDn;
    public String bindPassword;
    public String userDnTemplate;
    public String userSearchFilter;
    public String baseDn;
    public String groupSearchBase;
    public String groupSearchFilter;
    public String groupAuthPattern;
    public String referral;
    public boolean startTls;
    public String adUrl;
    public String adBaseDn;
    public String adBindDn;
    public String adBindPassword;
    public String adUserSearchFilter;
    public String adDomain;
}
