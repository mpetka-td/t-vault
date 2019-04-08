// =========================================================================
// Copyright 2019 T-Mobile, US
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// See the readme.txt file for additional language around disclaimer of warranties.
// =========================================================================

package com.tmobile.cso.vault.api.service;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmobile.cso.vault.api.model.*;
import org.apache.commons.collections.CollectionUtils;
import com.tmobile.cso.vault.api.utils.PolicyUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.ldap.filter.LikeFilter;
import org.springframework.ldap.filter.NotFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.tmobile.cso.vault.api.common.TVaultConstants;
import com.tmobile.cso.vault.api.controller.ControllerUtil;
import com.tmobile.cso.vault.api.exception.LogMessage;
import com.tmobile.cso.vault.api.process.RequestProcessor;
import com.tmobile.cso.vault.api.process.Response;
import com.tmobile.cso.vault.api.utils.JSONUtil;
import com.tmobile.cso.vault.api.utils.ThreadLocalContext;

@Component
public class  ServiceAccountsService {

	@Value("${vault.port}")
	private String vaultPort;

	private static Logger log = LogManager.getLogger(ServiceAccountsService.class);

	@Autowired
	@Qualifier(value = "svcAccLdapTemplate")
	private LdapTemplate ldapTemplate;
	
	@Autowired
	private AccessService accessService;

	@Autowired
	private RequestProcessor reqProcessor;

	@Autowired
	private PolicyUtils policyUtils;

	@Value("${ad.svc.acc.suffix:clouddev.corporate.t-mobile.com}")
	private String serviceAccountSuffix;
	
	@Value("${vault.auth.method}")
	private String vaultAuthMethod;
	
	@Value("${ad.username}")
	private String adMasterServiveAccount;
	
	/**
	 * Gets the list of users from Directory Server based on UPN
	 * @param UserPrincipalName
	 * @return
	 */
	public ResponseEntity<ADServiceAccountObjects> getADServiceAccounts(String token, UserDetails userDetails, String UserPrincipalName, boolean excludeOnboarded) {
		AndFilter andFilter = new AndFilter();
		andFilter.and(new LikeFilter("userPrincipalName", UserPrincipalName+"*"));
		andFilter.and(new EqualsFilter("objectClass", "user"));
		andFilter.and(new NotFilter(new EqualsFilter("CN", adMasterServiveAccount)));
		if (excludeOnboarded) {
			ResponseEntity<String> responseEntity = getOnboardedServiceAccounts(token, userDetails);
			if (HttpStatus.OK.equals(responseEntity.getStatusCode())) {
				String response = responseEntity.getBody();
				List<String> onboardedSvcAccs = new ArrayList<String>();
				try {
					Map<String, Object> requestParams = new ObjectMapper().readValue(response, new TypeReference<Map<String, Object>>(){});
					onboardedSvcAccs = (ArrayList<String>) requestParams.get("keys");
				}
				catch(Exception ex) {
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "getADServiceAccounts").
							put(LogMessage.MESSAGE, String.format("There are no service accounts currently onboarded or error in retrieving onboarded service accounts")).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
				}
				for (String onboardedSvcAcc: onboardedSvcAccs) {
					andFilter.and(new NotFilter(new EqualsFilter("CN", onboardedSvcAcc)));
				}
			}
		}
		List<ADServiceAccount> allServiceAccounts = getADServiceAccounts(andFilter);
		ADServiceAccountObjects adServiceAccountObjects = new ADServiceAccountObjects();
		ADServiceAccountObjectsList adServiceAccountObjectsList = new ADServiceAccountObjectsList();
		Object[] values = new Object[] {};
		if (!CollectionUtils.isEmpty(allServiceAccounts)) {
			values = allServiceAccounts.toArray(new ADServiceAccount[allServiceAccounts.size()]);
		}
		adServiceAccountObjectsList.setValues(values);
		adServiceAccountObjects.setData(adServiceAccountObjectsList);
		return ResponseEntity.status(HttpStatus.OK).body(adServiceAccountObjects);
	}
	
	/**
	 * Gets the list of ADAccounts from AD Server
	 * @param filter
	 * @return
	 */
	private List<ADServiceAccount> getADServiceAccounts(Filter filter) {
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "getAllAccounts").
				put(LogMessage.MESSAGE, String.format("Trying to get list of user accounts from AD server")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		return ldapTemplate.search("", filter.encode(), new AttributesMapper<ADServiceAccount>() {
			@Override
			public ADServiceAccount mapFromAttributes(Attributes attr) throws NamingException {
				ADServiceAccount adServiceAccount = new ADServiceAccount();
				if (attr != null) {
					String mail = ""; 
					if(attr.get("mail") != null) {
						mail = ((String) attr.get("mail").get());
					}
					String userId = ((String) attr.get("name").get());
					// Assign first part of the email id for use with UPN authentication
					if (!StringUtils.isEmpty(mail)) {
						userId = mail.substring(0, mail.indexOf("@"));
					}
					adServiceAccount.setUserId(userId);
					if (attr.get("displayname") != null) {
						adServiceAccount.setDisplayName(((String) attr.get("displayname").get()));
					}
					if (attr.get("givenname") != null) {
						adServiceAccount.setGivenName(((String) attr.get("givenname").get()));
					}

					if (attr.get("mail") != null) {
						adServiceAccount.setUserEmail(((String) attr.get("mail").get()));
					}
					if (attr.get("name") != null) {
						adServiceAccount.setUserName((String) attr.get("name").get());
					}
					if (attr.get("whenCreated") != null) {
						String rawDateTime = (String) attr.get("whenCreated").get();
						DateTimeFormatter fmt = DateTimeFormatter.ofPattern ( "uuuuMMddHHmmss[,S][.S]X" );
						OffsetDateTime odt = OffsetDateTime.parse (rawDateTime, fmt);
						Instant instant = odt.toInstant();
						adServiceAccount.setWhenCreated(instant);
					}
					if (attr.get("manager") != null) {
						String managedByStr = (String) attr.get("manager").get();
						String managedBy= managedByStr.substring(3, managedByStr.indexOf(","));
						adServiceAccount.setManagedBy(managedBy);
						adServiceAccount.setOwner(managedBy.toLowerCase());
					}
					if (attr.get("accountExpires") != null) {
						String rawExpDateTime = (String) attr.get("accountExpires").get();
						String sAccountExpiration = "Never";
						try {
							long lAccountExpiration = Long.parseLong(rawExpDateTime);
							long timeAdjust=9223372036854775807L;
							Date pwdSet = new Date(lAccountExpiration/10000-timeAdjust);
							DateFormat mydate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
							sAccountExpiration = mydate.format(pwdSet);
						}
						catch(Exception ex) {
							//TODO
						}
						adServiceAccount.setAccountExpires(sAccountExpiration);
					}
					
					if (attr.get("pwdLastSet") != null) {
						String pwdLastSetRaw = (String) attr.get("pwdLastSet").get();
						String pwsLastSet = null;
						try {
							long lpwdLastSetRaw = Long.parseLong(pwdLastSetRaw);
							long timeAdjust=9223372036854775807L;
							Date pwdSet = new Date(lpwdLastSetRaw/10000-timeAdjust);
							DateFormat mydate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
							pwsLastSet = mydate.format(pwdSet);
						}
						catch(Exception ex) {
							//TODO
						}
						adServiceAccount.setPwdLastSet(pwsLastSet);
					}
					//TODO: The below values are to be calculated
					adServiceAccount.setMaxPwdAge(getPasswordMaxAge());
					adServiceAccount.setAccountStatus("active");
					adServiceAccount.setLockStatus("active");
					adServiceAccount.setPasswordExpiry("2019-05-18 11:59:59");
					if (attr.get("description") != null) {
						adServiceAccount.setPurpose((String) attr.get("description").get());
					}
				}
				return adServiceAccount;
			}
		});
	}
	/**
	 * Gets the Max Password Age from AD Password policy
	 * @return
	 */
	private int getPasswordMaxAge() {
		//TODO Actual implementation should be based on AD Password policy
		int pwdMaxAge = 90;
		return pwdMaxAge;
	}
	/**
	 * Onboards an AD service account into TVault for password rotation
	 * @param serviceAccount
	 * @return
	 */
	public ResponseEntity<String> onboardServiceAccount(String token, ServiceAccount serviceAccount, UserDetails userDetails) {
		if (serviceAccount.isAutoRotate()) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "onboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Auto-Rotate of password has been turned on")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			if (serviceAccount.getTtl() > serviceAccount.getMax_ttl()) {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "onboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("TTL is [%s] is greater the MAX_TTL [%s]", serviceAccount.getTtl(), serviceAccount.getMax_ttl())).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Password TTL can't be more than MAX_TTL\"]}");
			}
			if (serviceAccount.getTtl() > TVaultConstants.PASSWORD_AUTOROTATE_TTL_MAX_VALUE) {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "onboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("TTL is [%s] is greater the Maximum allowed [%s]", serviceAccount.getTtl(), TVaultConstants.PASSWORD_AUTOROTATE_TTL_MAX_VALUE)).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Password TTL can't be more than "+TVaultConstants.PASSWORD_AUTOROTATE_TTL_MAX_VALUE+" seconds\"]}");
			}
		}
		else {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "onboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Auto-Rotate of password has been turned off")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"TO BE IMPLEMENTED: Auto-Rotate of password has been turned off and this is yet to be implemented\"]}");
		}
		ResponseEntity<String> accountRoleCreationResponse = createAccountRole(token, serviceAccount);
		if(accountRoleCreationResponse.getStatusCode().equals(HttpStatus.OK)) {
			// Create Metadata
			ResponseEntity<String> metadataCreationResponse = createMetadata(token, serviceAccount);
			if (HttpStatus.OK.equals(metadataCreationResponse.getStatusCode())) {
				log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "onboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("Successfully created Metadata for the Service Account")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
			}
			else {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "onboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("Successfully created Service Account Role. However creation of Metadata failed.")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"errors\":[\"Successfully created Service Account Role. However creation of Metadata failed.\"]}");
			}
			String svcAccName = serviceAccount.getName();
			ResponseEntity<String> svcAccPolicyCreationResponse = createServiceAccountPolicies(token, svcAccName);
			if (HttpStatus.OK.equals(svcAccPolicyCreationResponse.getStatusCode())) {
				ServiceAccountUser serviceAccountUser = new ServiceAccountUser(svcAccName, serviceAccount.getOwner(), TVaultConstants.SUDO_POLICY);
				ResponseEntity<String> addUserToServiceAccountResponse = addUserToServiceAccount(token, serviceAccountUser, userDetails);
				if (HttpStatus.OK.equals(addUserToServiceAccountResponse.getStatusCode())) {
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "onboardServiceAccount").
							put(LogMessage.MESSAGE, String.format ("Successfully completed onboarding of AD service account into TVault for password rotation.")).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully completed onboarding of AD service account into TVault for password rotation.\"]}");
				}
				else {
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "onboardServiceAccount").
							put(LogMessage.MESSAGE, String.format ("Successfully created Service Account Role and policies. However the association of owner information failed.")).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"messages\":[\"Successfully created Service Account Role and policies. However the association of owner information failed.\"]}");
				}
			}
			else {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "onboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("Failed to onboard AD service account into TVault for password rotation.")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				// TODO: Revert the ServiceAccountRole creation...
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to onboard AD service account into TVault for password rotation.\"]}");
			}
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "onboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Failed to onboard AD service account into TVault for password rotation.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to onboard AD service account into TVault for password rotation.\"]}");
		}
	}

	/**
	 * To create Metadata for the Service Account
	 * @param token
	 * @param serviceAccount
	 * @return
	 */
	private ResponseEntity<String> createMetadata(String token, ServiceAccount serviceAccount) {
		String svcAccMetaDataJson = populateSvcAccMetaJson(serviceAccount.getName(), serviceAccount.getOwner());
		boolean svcAccMetaDataCreationStatus = ControllerUtil.createMetadata(svcAccMetaDataJson, token);
		if(svcAccMetaDataCreationStatus){
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "createMetadata").
					put(LogMessage.MESSAGE, String.format("Successfully created metadata for the Service Account [%s]", serviceAccount.getName())).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully created Metadata for the Service Account\"]}");
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "createAccountRole").
					put(LogMessage.MESSAGE, "Unable to create Metadata for the Service Account").
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to create Metadata for the Service Account\"]}");
	}
	/**
	 * To delete metadata for the service account
	 * @param token
	 * @param serviceAccount
	 * @return
	 */
	private ResponseEntity<String> deleteMetadata(String token, OnboardedServiceAccount serviceAccount) {
		String svcAccMetaDataJson = populateSvcAccMetaJson(serviceAccount.getName(), serviceAccount.getOwner());
		Response svcAccMetaDataJsonDeletionResponse = reqProcessor.process("/delete",svcAccMetaDataJson,token);
		if(HttpStatus.OK.equals(svcAccMetaDataJsonDeletionResponse.getHttpstatus()) || HttpStatus.NO_CONTENT.equals(svcAccMetaDataJsonDeletionResponse.getHttpstatus())){
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "deleteMetadata").
					put(LogMessage.MESSAGE, String.format("Successfully deleted metadata for the Service Account [%s]", serviceAccount.getName())).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully deleted Metadata for the Service Account\"]}");
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "deleteMetadata").
					put(LogMessage.MESSAGE, "Unable to delete Metadata for the Service Account").
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to delete Metadata for the Service Account\"]}");
	}

	/**
	 * Helper to generate input JSON for Service Account metadata
	 * @param svcAccName
	 * @param username
	 * @return
	 */
	private String populateSvcAccMetaJson(String svcAccName, String username) {
		String _path = TVaultConstants.SVC_ACC_ROLES_METADATA_MOUNT_PATH + "/" + svcAccName;
		ServiceAccountMetadataDetails serviceAccountMetadataDetails = new ServiceAccountMetadataDetails(svcAccName);
		serviceAccountMetadataDetails.setManagedBy(username);
		ServiceAccountMetadata serviceAccountMetadata =  new ServiceAccountMetadata(_path, serviceAccountMetadataDetails);
		String jsonStr = JSONUtil.getJSON(serviceAccountMetadata);
		Map<String,Object> rqstParams = ControllerUtil.parseJson(jsonStr);
		rqstParams.put("path",_path);
		return ControllerUtil.convetToJson(rqstParams);
	}

	/**
	 * Offboards an AD service account from TVault for password rotation
	 * @param token
	 * @param serviceAccount
	 * @param userDetails
	 * @return
	 */
	public ResponseEntity<String> offboardServiceAccount(String token, OnboardedServiceAccount serviceAccount, UserDetails userDetails) {
		String svcAccName = serviceAccount.getName();
		ServiceAccountUser serviceAccountUser = new ServiceAccountUser(svcAccName, serviceAccount.getOwner(), TVaultConstants.SUDO_POLICY);
		// Remove the owner association (owner policy)
		ResponseEntity<String> removeUserFromServiceAccountResponse = removeUserFromServiceAccount(token, serviceAccountUser, userDetails);
		if (!HttpStatus.OK.equals(removeUserFromServiceAccountResponse.getStatusCode())) {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "offboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Failed to remove the user from the Service Account")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
		}
		ResponseEntity<String> svcAccPolicyDeletionResponse = deleteServiceAccountPolicies(token, svcAccName);
		if (!HttpStatus.OK.equals(svcAccPolicyDeletionResponse.getStatusCode())) {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "offboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Failed to delete some of the policies for service account")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
		}
		// delete users,groups,aws-roles,app-roles from service account
		String _path = TVaultConstants.SVC_ACC_ROLES_METADATA_MOUNT_PATH + "/" + svcAccName;
		Response metaResponse = reqProcessor.process("/sdb","{\"path\":\""+_path+"\"}",token);
		Map<String, Object> responseMap = null;
		try {
			responseMap = new ObjectMapper().readValue(metaResponse.getResponse(), new TypeReference<Map<String, Object>>(){});
		} catch (IOException e) {
			log.error(e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Error Fetching existing service account info \"]}");
		}
		if(responseMap!=null && responseMap.get("data")!=null){
			Map<String,Object> metadataMap = (Map<String,Object>)responseMap.get("data");
			Map<String,String> awsroles = (Map<String, String>)metadataMap.get("aws-roles");
			Map<String,String> approles = (Map<String, String>)metadataMap.get("app-roles");
			Map<String,String> groups = (Map<String, String>)metadataMap.get("groups");
			Map<String,String> users = (Map<String, String>) metadataMap.get("users");
			// always add owner to the users list whose policy should be updated
			String managedBy = (String) metadataMap.get("managedBy");
			if (!org.apache.commons.lang3.StringUtils.isEmpty(managedBy)) {
				users.put(managedBy, "sudo");
			}
			ControllerUtil.updateUserPolicyAssociationOnSvcaccDelete(svcAccName,users,token);
			ControllerUtil.updateGroupPolicyAssociationOnSvcaccDelete(svcAccName,groups,token);
			//ControllerUtil.deleteAwsRoleAssociateionOnSvcaccDelete(path,awsroles,token);
			//ControllerUtil.updateApprolePolicyAssociationOnSvcaccDelete(path,groups,token);
		}
		ResponseEntity<String> accountRoleDeletionResponse = deleteAccountRole(token, serviceAccount);
		if (HttpStatus.OK.equals(accountRoleDeletionResponse.getStatusCode())) {
			// Remove metadata...
			ResponseEntity<String> metadataUpdateResponse =  deleteMetadata(token, serviceAccount);
			if (HttpStatus.OK.equals(metadataUpdateResponse.getStatusCode())) {
				log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "offboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("Successfully completed offboarding of AD service account from TVault for password rotation.")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully completed offboarding of AD service account from TVault for password rotation.\"]}");
			}
			else {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "offboardServiceAccount").
						put(LogMessage.MESSAGE, String.format ("Unable to delete Metadata for the Service Account")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"errors\":[\"Failed to offboard AD service account from TVault for password rotation.\"]}");
			}
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "offboardServiceAccount").
					put(LogMessage.MESSAGE, String.format ("Failed to offboard AD service account from TVault for password rotation.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"errors\":[\"Failed to offboard AD service account from TVault for password rotation.\"]}");
		}
	}

	/**
	 * Create Service Account Role
	 * @param token
	 * @param serviceAccount
	 * @return
	 */
	private ResponseEntity<String> createAccountRole(String token, ServiceAccount serviceAccount) {
		ServiceAccountTTL serviceAccountTTL = new ServiceAccountTTL();
		serviceAccountTTL.setRole_name(serviceAccount.getName());
		serviceAccountTTL.setService_account_name(serviceAccount.getName() + "@"+ serviceAccountSuffix) ;
		serviceAccountTTL.setTtl(serviceAccount.getTtl());
		String svc_account_payload = JSONUtil.getJSON(serviceAccountTTL);
		Response onboardingResponse = reqProcessor.process("/ad/serviceaccount/onboard", svc_account_payload, token);
		if(onboardingResponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || onboardingResponse.getHttpstatus().equals(HttpStatus.OK)) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "createAccountRole").
					put(LogMessage.MESSAGE, String.format ("Successfully created service account role.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully created service account role.\"]}");
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "createAccountRole").
					put(LogMessage.MESSAGE, String.format ("Failed to create service account role.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to create service account role.\"]}");
		}
	}
	/**
	 * Deletes the Account Role
	 * @param token
	 * @param serviceAccount
	 * @return
	 */
	private ResponseEntity<String> deleteAccountRole(String token, OnboardedServiceAccount serviceAccount) {
		ServiceAccountTTL serviceAccountTTL = new ServiceAccountTTL();
		serviceAccountTTL.setRole_name(serviceAccount.getName());
		serviceAccountTTL.setService_account_name(serviceAccount.getName() + "@"+ serviceAccountSuffix) ;
		String svc_account_payload = JSONUtil.getJSON(serviceAccountTTL);
		Response onboardingResponse = reqProcessor.process("/ad/serviceaccount/offboard", svc_account_payload, token);
		if(onboardingResponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || onboardingResponse.getHttpstatus().equals(HttpStatus.OK)) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "deleteAccountRole").
					put(LogMessage.MESSAGE, String.format ("Successfully deleted service account role.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully deleted service account role.\"]}");
		}
		else {
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "deleteAccountRole").
					put(LogMessage.MESSAGE, String.format ("Failed to delete service account role.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to delete service account role.\"]}");
		}
	}

	/**
	 * Create policies for service account
	 * @param token
	 * @param svcAccName
	 * @return
	 */
	private  ResponseEntity<String> createServiceAccountPolicies(String token, String svcAccName) {
		int succssCount = 0;
		for (String policyPrefix : TVaultConstants.getSvcAccPolicies().keySet()) {
			AccessPolicy accessPolicy = new AccessPolicy();
			String accessId = new StringBuffer().append(policyPrefix).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			accessPolicy.setAccessid(accessId);
			HashMap<String,String> accessMap = new HashMap<String,String>();
			String svcAccCredsPath=new StringBuffer().append(TVaultConstants.SVC_ACC_CREDS_PATH).append(svcAccName).toString();
			accessMap.put(svcAccCredsPath, TVaultConstants.getSvcAccPolicies().get(policyPrefix));
			accessPolicy.setAccess(accessMap);
			ResponseEntity<String> policyCreationStatus = accessService.createPolicy(token, accessPolicy);
			if (HttpStatus.OK.equals(policyCreationStatus.getStatusCode())) {
				succssCount++;
			}
		}
		if (succssCount == TVaultConstants.getSvcAccPolicies().size()) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "createServiceAccountPolicies").
					put(LogMessage.MESSAGE, String.format ("Successfully created policies for service account.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully created policies for service account\"]}");
		}
		log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "createServiceAccountPolicies").
				put(LogMessage.MESSAGE, String.format ("Failed to create some of the policies for service account.")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"messages\":[\"Failed to create some of the policies for service account\"]}");
	}
	/**
	 * Deletes Service Account policies
	 * @param token
	 * @param svcAccName
	 * @return
	 */
	private  ResponseEntity<String> deleteServiceAccountPolicies(String token, String svcAccName) {
		int succssCount = 0;
		for (String policyPrefix : TVaultConstants.getSvcAccPolicies().keySet()) {
			String accessId = new StringBuffer().append(policyPrefix).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			ResponseEntity<String> policyCreationStatus = accessService.deletePolicyInfo(token, accessId);
			if (HttpStatus.OK.equals(policyCreationStatus.getStatusCode())) {
				succssCount++;
			}
		}
		if (succssCount == TVaultConstants.getSvcAccPolicies().size()) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "deleteServiceAccountPolicies").
					put(LogMessage.MESSAGE, String.format ("Successfully created policies for service account.")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully removed policies for service account\"]}");
		}
		log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "deleteServiceAccountPolicies").
				put(LogMessage.MESSAGE, String.format ("Failed to delete some of the policies for service account.")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		return ResponseEntity.status(HttpStatus.MULTI_STATUS).body("{\"messages\":[\"Failed to delete some of the policies for service account\"]}");
	}
	/**
	 * Adds the user to a service account
	 * @param token
	 * @param serviceAccount
	 * @param userDetails
	 * @return
	 */
	public ResponseEntity<String> addUserToServiceAccount(String token, ServiceAccountUser serviceAccountUser, UserDetails userDetails) {
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "Add User to ServiceAccount").
				put(LogMessage.MESSAGE, String.format ("Trying to add user to ServiceAccount")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));

		String userName = serviceAccountUser.getUsername();
		String svcAccName = serviceAccountUser.getSvcAccName();
		String access = serviceAccountUser.getAccess();

		if(!ControllerUtil.areSvcUserInputsValid(serviceAccountUser)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid input values\"]}");
		}

		// TODO: Validation for String expectedPath = TVaultConstants.SVC_ACC_CREDS_PATH+svcAccName;

		userName = (userName !=null) ? userName.toLowerCase() : userName;
		access = (access != null) ? access.toLowerCase(): access;
		if(!TVaultConstants.SVC_ACC_POLICIES_PREFIXES.containsValue(access)){
			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add User to Service Account").
					put(LogMessage.MESSAGE, String.format ("Incorrect access requested. Valid values are read,write,deny,owner")).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"errors\":[\"Incorrect access requested. Valid values are read,write,deny,owner \"]}");
		}
		boolean isAuthorized = true;
		if (userDetails != null) {
			isAuthorized = canAddOrRemoveUser(userDetails, serviceAccountUser, TVaultConstants.ADD_USER);
		}

		if(isAuthorized){
			String policy = TVaultConstants.EMPTY;
			policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(access)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add User to Service Account").
					put(LogMessage.MESSAGE, String.format ("policy is [%s]", policy)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			String r_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.READ_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String w_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.WRITE_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String d_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.DENY_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add User to Service Account").
					put(LogMessage.MESSAGE, String.format ("Policies are, read - [%s], write - [%s], deny -[%s], owner - [%s]", r_policy, w_policy, d_policy, o_policy)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			Response userResponse;
			if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
				userResponse = reqProcessor.process("/auth/userpass/read","{\"username\":\""+userName+"\"}",token);	
			}
			else {
				userResponse = reqProcessor.process("/auth/ldap/users","{\"username\":\""+userName+"\"}",token);
			}

			String responseJson="";
			String groups="";
			List<String> policies = new ArrayList<>();
			List<String> currentpolicies = new ArrayList<>();

			if(HttpStatus.OK.equals(userResponse.getHttpstatus())){
				responseJson = userResponse.getResponse();	
				try {
					ObjectMapper objMapper = new ObjectMapper();
					currentpolicies = ControllerUtil.getPoliciesAsListFromJson(objMapper, responseJson);
					if (!(TVaultConstants.USERPASS.equals(vaultAuthMethod))) {
						groups =objMapper.readTree(responseJson).get("data").get("groups").asText();
					}
				} catch (IOException e) {
					log.error(e);
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add User to ServiceAccount").
							put(LogMessage.MESSAGE, String.format ("Exception while creating currentpolicies or groups")).
							put(LogMessage.STACKTRACE, Arrays.toString(e.getStackTrace())).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
				}

				policies.addAll(currentpolicies);
				policies.remove(r_policy);
				policies.remove(w_policy);
				policies.remove(d_policy);
				policies.add(policy);
			}else{
				// New user to be configured
				policies.add(policy);
			}
			String policiesString = org.apache.commons.lang3.StringUtils.join(policies, ",");

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add User to ServiceAccount").
					put(LogMessage.MESSAGE, String.format ("policies [%s] before calling configureUserpassUser/configureLDAPUser", policies)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			Response ldapConfigresponse;
			if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
				ldapConfigresponse = ControllerUtil.configureUserpassUser(userName,policiesString,token);
			}
			else {
				ldapConfigresponse = ControllerUtil.configureLDAPUser(userName,policiesString,groups,token);
			}
			if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || ldapConfigresponse.getHttpstatus().equals(HttpStatus.OK)){
				// User has been associated with Service Account. Now metadata has to be created
				String path = new StringBuffer(TVaultConstants.SVC_ACC_ROLES_PATH).append("/").append(svcAccName).toString();
				Map<String,String> params = new HashMap<String,String>();
				params.put("type", "users");
				params.put("name",serviceAccountUser.getUsername());
				params.put("path",path);
				params.put("access",access);
				Response metadataResponse = ControllerUtil.updateMetadata(params,token);
				if(metadataResponse != null && HttpStatus.NO_CONTENT.equals(metadataResponse.getHttpstatus()) || HttpStatus.OK.equals(metadataResponse.getHttpstatus())){
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add User to ServiceAccount").
							put(LogMessage.MESSAGE, "User is successfully associated with Service Account").
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully added user to the Service Account\"]}");
				} else{
					//Revert the user association...
					// TODO: Revert the user association without metadata update...
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add User to ServiceAccount").
							put(LogMessage.MESSAGE, "Metadata creation for user association with service account failed").
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return removeUserFromServiceAccount(token, serviceAccountUser, userDetails);
				}
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to add user to the Service Account\"]}");
			}
			
		}else{
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Not authorized to perform\"]}");
		}
	}
	/**
	 * Removes user from service account
	 * @param token
	 * @param safeUser
	 * @return
	 */
	public ResponseEntity<String> removeUserFromServiceAccount(String token, ServiceAccountUser serviceAccountUser, UserDetails userDetails) {
		String userName = serviceAccountUser.getUsername();
		String svcAccName = serviceAccountUser.getSvcAccName();
		String access = serviceAccountUser.getAccess();

		if(!ControllerUtil.areSvcUserInputsValid(serviceAccountUser)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid input values\"]}");
		}

		boolean isAuthorized = true;
		if (userDetails != null) {
			isAuthorized = canAddOrRemoveUser(userDetails, serviceAccountUser, TVaultConstants.REMOVE_USER);
		}

		if(isAuthorized){

			String r_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.READ_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String w_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.WRITE_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String d_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.DENY_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Remove user from Service Account").
					put(LogMessage.MESSAGE, String.format ("Policies are, read - [%s], write - [%s], deny -[%s], owner - [%s]", r_policy, w_policy, d_policy, o_policy)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			Response userResponse;
			if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
				userResponse = reqProcessor.process("/auth/userpass/read","{\"username\":\""+userName+"\"}",token);	
			}
			else {
				userResponse = reqProcessor.process("/auth/ldap/users","{\"username\":\""+userName+"\"}",token);
			}

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Remove user from ServiceAccount").
					put(LogMessage.MESSAGE, String.format ("userResponse status is [%s]", userResponse.getHttpstatus())).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));

			String responseJson="";
			String groups="";
			List<String> policies = new ArrayList<>();
			List<String> currentpolicies = new ArrayList<>();
			String policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(access)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			if(HttpStatus.OK.equals(userResponse.getHttpstatus())){
				responseJson = userResponse.getResponse();	
				try {
					ObjectMapper objMapper = new ObjectMapper();
					currentpolicies = ControllerUtil.getPoliciesAsListFromJson(objMapper, responseJson);
					if (!(TVaultConstants.USERPASS.equals(vaultAuthMethod))) {
						groups =objMapper.readTree(responseJson).get("data").get("groups").asText();
					}
				} catch (IOException e) {
					log.error(e);
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Remove User from ServiceAccount").
							put(LogMessage.MESSAGE, String.format ("Exception while creating currentpolicies or groups")).
							put(LogMessage.STACKTRACE, Arrays.toString(e.getStackTrace())).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
				}
				policies.addAll(currentpolicies);
				policies.remove(policy);
			}
			String policiesString = org.apache.commons.lang3.StringUtils.join(policies, ",");

			Response ldapConfigresponse;
			if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
				ldapConfigresponse = ControllerUtil.configureUserpassUser(userName,policiesString,token);
			}
			else {
				ldapConfigresponse = ControllerUtil.configureLDAPUser(userName,policiesString,groups,token);
			}
			if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || ldapConfigresponse.getHttpstatus().equals(HttpStatus.OK)){
				// User has been associated with Service Account. Now metadata has to be deleted
				String path = new StringBuffer(TVaultConstants.SVC_ACC_ROLES_PATH).append("/").append(svcAccName).toString();
				Map<String,String> params = new HashMap<String,String>();
				params.put("type", "users");
				params.put("name",serviceAccountUser.getUsername());
				params.put("path",path);
				params.put("access","delete");
				Response metadataResponse = ControllerUtil.updateMetadata(params,token);
				if(metadataResponse != null && HttpStatus.NO_CONTENT.equals(metadataResponse.getHttpstatus()) || HttpStatus.OK.equals(metadataResponse.getHttpstatus())){
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Remove User to ServiceAccount").
							put(LogMessage.MESSAGE, "User is successfully Removed from Service Account").
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Successfully removed user from the Service Account\"]}");
				}
				return ResponseEntity.status(HttpStatus.OK).body("{\"message\":[\"Successfully removed user from the Service Account. Meta data update failed.\"]}");
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to remvoe the user from the Service Account\"]}");
			}	
		}
		else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Not authorized to perform\"]}");
		}
	}
	/**
	 * To reset Service Account Password
	 * @param token
	 * @param svcAccName
	 * @param resetPassword
	 * @return
	 */
	public ResponseEntity<String> resetSvcAccPassword(String token, String svcAccName, UserDetails userDetails){
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "readSvcAccPassword").
				put(LogMessage.MESSAGE, String.format("Trying to read service account password [%s]", svcAccName)).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		Response response = reqProcessor.process("/ad/serviceaccount/reset","{\"role_name\":\""+svcAccName+"\"}",token);
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "Read Secret").
				put(LogMessage.MESSAGE, String.format("Successfully read  service account password for [%s]", svcAccName)).
				put(LogMessage.STATUS, response.getHttpstatus().toString()).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
	}
	/**
	 * Gets the details of a service account that is already onboarded into TVault
	 * @param token
	 * @param svcAccName
	 * @param userDetails
	 * @return
	 */
	public ResponseEntity<String> getOnboarderdServiceAccount(String token, String svcAccName, UserDetails userDetails){
		OnboardedServiceAccountDetails onbSvcAccDtls = getOnboarderdServiceAccountDetails(token, svcAccName);
		if (onbSvcAccDtls != null) {
			String onbSvcAccDtlsJson = JSONUtil.getJSON(onbSvcAccDtls);
			return ResponseEntity.status(HttpStatus.OK).body(onbSvcAccDtlsJson);
		}
		else {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"errors\":[\"Either Service Account is not onbaorderd or you don't have enough permission to read\"]}");
		}
	}
	/**
	 * Gets the details of an onboarded service account
	 * @return
	 */
	private OnboardedServiceAccountDetails getOnboarderdServiceAccountDetails(String token, String svcAccName) {
		OnboardedServiceAccountDetails onbSvcAccDtls = null;
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
			      put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				  put(LogMessage.ACTION, "getOnboardedServiceAccountDetails").
			      put(LogMessage.MESSAGE, String.format("Trying to get onboaded service account details")).
			      put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
			      build()));
		Response svcAccDtlsresponse = reqProcessor.process("/ad/serviceaccount/details","{\"role_name\":\""+svcAccName+"\"}",token);
		if (HttpStatus.OK.equals(svcAccDtlsresponse.getHttpstatus())) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				      put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					  put(LogMessage.ACTION, "getOnboardedServiceAccountDetails").
				      put(LogMessage.MESSAGE, "Successfully retrieved the Service Account details").
				      put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				      build()));
			try {
				String response = svcAccDtlsresponse.getResponse();
				Map<String, Object> requestParams = new ObjectMapper().readValue(response, new TypeReference<Map<String, Object>>(){});
				
				String accName =  (String) requestParams.get("service_account_name");
				Integer accTTL = (Integer)requestParams.get("ttl");
				String accLastPwdRotation = (String) requestParams.get("last_vault_rotation");
				String accLastPwd = (String) requestParams.get("last_password");
				onbSvcAccDtls = new OnboardedServiceAccountDetails();
				onbSvcAccDtls.setName(accName);
				try {
					onbSvcAccDtls.setTtl(new Long(accTTL));
				} catch (Exception e) {
				}
				onbSvcAccDtls.setLastVaultRotation(accLastPwdRotation);
				onbSvcAccDtls.setPasswordLastSet(accLastPwd);
			}
			catch(Exception ex) {
				log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "getADServiceAccounts").
						put(LogMessage.MESSAGE, String.format("There are no service accounts currently onboarded or error in retrieving onboarded service accounts")).
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
				
			}
			return onbSvcAccDtls;
		}
		else {
			return onbSvcAccDtls;
		}
	}
	/**
	 * 
	 * @param userDetails
	 * @param serviceAccountUser
	 * @param action
	 * @return
	 */
	public boolean canAddOrRemoveUser(UserDetails userDetails, ServiceAccountUser serviceAccountUser, String action) {
		if (userDetails != null && userDetails.isAdmin()) {
			// Admin is always authorized to add/remove user
			return true;
		}
		else {
			//TODO: Implementation to be completed...
			// Get the policies for the current users
			// Get the serviceAccountName from serviceAccountUser
			// If there is owner policy for the serviceAccountName, then this owner can add/remove user
			return false;
		}
	}
	/**
	 * To get list of service accounts
	 * @param token
	 * @param userDetails
	 * @return
	 */
	public ResponseEntity<String> getOnboardedServiceAccounts(String token,  UserDetails userDetails) {
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
			      put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				  put(LogMessage.ACTION, "listOnboardedServiceAccounts").
			      put(LogMessage.MESSAGE, String.format("Trying to get list of onboaded service accounts")).
			      put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
			      build()));
		Response response = null;
		if (userDetails.isAdmin()) {
			response = reqProcessor.process("/ad/serviceaccount/onboardedlist","{}",token);
		}
		else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"TO BE IMPLEMENTED for non admin user\"]}");
		}

		if (HttpStatus.OK.equals(response.getHttpstatus())) {
			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				      put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					  put(LogMessage.ACTION, "listOnboardedServiceAccounts").
				      put(LogMessage.MESSAGE, "Successfully retrieved the list of Service Accounts").
				      put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				      build()));
			return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
		}
		else if (HttpStatus.NOT_FOUND.equals(response.getHttpstatus())) {
			return ResponseEntity.status(HttpStatus.OK).body("{\"keys\":[]}");
		}
		return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
	}

    /**
     * Check if user has the permission to add user/group/awsrole/approles to the Service Account
     * @param userDetails
     * @param action
     * @param token
     * @return
     */
    public boolean hasAddOrRemovePermission(UserDetails userDetails, String serviceAccount, String token) {
        // Owner of the service account or admin user can add/remove group to service account
        if (userDetails.isAdmin()) {
            return true;
        }
        String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(serviceAccount).toString();
        String [] policies = policyUtils.getCurrentPolicies(token, userDetails.getUsername());
        if (ArrayUtils.contains(policies, o_policy)) {
            return true;
        }
        return false;
    }

    /**
     * Add Group to Service Account
     * @param token
     * @param serviceAccountGroup
     * @param userDetails
     * @return
     */
	public ResponseEntity<String> addGroupToServiceAccount(String token, ServiceAccountGroup serviceAccountGroup, UserDetails userDetails) {

		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "Add Group to Service Account").
				put(LogMessage.MESSAGE, String.format ("Trying to add Group to Service Account")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));

        if(!ControllerUtil.areSvcaccGroupInputsValid(serviceAccountGroup)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid input values\"]}");
        }

		String groupName = serviceAccountGroup.getGroupname();
		String svcAccName = serviceAccountGroup.getSvcAccName();
		String access = serviceAccountGroup.getAccess();

		if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"This operation is not supported for Userpass authentication. \"]}");
		}

		groupName = (groupName !=null) ? groupName.toLowerCase() : groupName;
		access = (access != null) ? access.toLowerCase(): access;

		boolean canAddGroup = hasAddOrRemovePermission(userDetails, svcAccName, token);
		if(canAddGroup){

			String policy = TVaultConstants.EMPTY;
			policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(access)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add Group to Service Account").
					put(LogMessage.MESSAGE, String.format ("policy is [%s]", policy)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));
			String r_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.READ_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String w_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.WRITE_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String d_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.DENY_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
			String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

			log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add Group to Service Account").
					put(LogMessage.MESSAGE, String.format ("Policies are, read - [%s], write - [%s], deny -[%s], owner - [%s]", r_policy, w_policy, d_policy, o_policy)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));

			Response groupResp = reqProcessor.process("/auth/ldap/groups","{\"groupname\":\""+groupName+"\"}",token);

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add Group to ServiceAccount").
					put(LogMessage.MESSAGE, String.format ("userResponse status is [%s]", groupResp.getHttpstatus())).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));

			String responseJson="";
			List<String> policies = new ArrayList<>();
			List<String> currentpolicies = new ArrayList<>();

			if(HttpStatus.OK.equals(groupResp.getHttpstatus())){
				responseJson = groupResp.getResponse();
				try {
					ObjectMapper objMapper = new ObjectMapper();
					currentpolicies = ControllerUtil.getPoliciesAsListFromJson(objMapper, responseJson);
				} catch (IOException e) {
					log.error(e);
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to ServiceAccount").
							put(LogMessage.MESSAGE, String.format ("Exception while creating currentpolicies")).
							put(LogMessage.STACKTRACE, Arrays.toString(e.getStackTrace())).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
				}

				policies.addAll(currentpolicies);
				policies.remove(r_policy);
				policies.remove(w_policy);
				policies.remove(d_policy);
				policies.add(policy);
			}else{
				// New group to be configured
				policies.add(policy);
			}
			String policiesString = org.apache.commons.lang3.StringUtils.join(policies, ",");
			String currentpoliciesString = org.apache.commons.lang3.StringUtils.join(currentpolicies, ",");

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add Group to ServiceAccount").
					put(LogMessage.MESSAGE, String.format ("policies [%s] before calling configureLDAPGroup", policies)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));

			Response ldapConfigresponse = ControllerUtil.configureLDAPGroup(groupName,policiesString,token);

			if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || ldapConfigresponse.getHttpstatus().equals(HttpStatus.OK)){
				String path = new StringBuffer(TVaultConstants.SVC_ACC_ROLES_PATH).append("/").append(svcAccName).toString();
				Map<String,String> params = new HashMap<String,String>();
				params.put("type", "groups");
				params.put("name",groupName);
				params.put("path",path);
				params.put("access",access);

				Response metadataResponse = ControllerUtil.updateMetadata(params,token);
				if(metadataResponse !=null && HttpStatus.NO_CONTENT.equals(metadataResponse.getHttpstatus())){
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to Service Account").
							put(LogMessage.MESSAGE, "Group configuration Success.").
							put(LogMessage.STATUS, metadataResponse.getHttpstatus().toString()).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Group is successfully associated with Service Account\"]}");
				}
				ldapConfigresponse = ControllerUtil.configureLDAPGroup(groupName,currentpoliciesString,token);
				if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT)){
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to Service Account").
							put(LogMessage.MESSAGE, "Reverting, group policy update success").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Group configuration failed.Please try again\"]}");
				}else{
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to Service Account").
							put(LogMessage.MESSAGE, "Reverting group policy update failed").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Group configuration failed.Contact Admin \"]}");
				}
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to add group to the Service Account\"]}");
			}
		}else{
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Access denied: No permission to add groups to this service account\"]}");
		}
	}

    /**
     * Remove Group Service Account
     * @param token
     * @param serviceAccountGroup
     * @param userDetails
     * @return
     */
    public ResponseEntity<String> removeGroupFromServiceAccount(String token, ServiceAccountGroup serviceAccountGroup, UserDetails userDetails) {
        log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                put(LogMessage.ACTION, "Remove Group from Service Account").
                put(LogMessage.MESSAGE, String.format ("Trying to remove Group from Service Account")).
                put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                build()));

        if(!ControllerUtil.areSvcaccGroupInputsValid(serviceAccountGroup)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid input values\"]}");
        }

        String groupName = serviceAccountGroup.getGroupname();
        String svcAccName = serviceAccountGroup.getSvcAccName();
        String access = serviceAccountGroup.getAccess();


        boolean isAuthorized = true;
        if (userDetails != null) {
            isAuthorized = hasAddOrRemovePermission(userDetails, svcAccName, token);
        }

        if(isAuthorized){

            String r_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.READ_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String w_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.WRITE_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String d_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.DENY_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

            log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                    put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                    put(LogMessage.ACTION, "Remove group from Service Account").
                    put(LogMessage.MESSAGE, String.format ("Policies are, read - [%s], write - [%s], deny -[%s], owner - [%s]", r_policy, w_policy, d_policy, o_policy)).
                    put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                    build()));
            Response groupResp = reqProcessor.process("/auth/ldap/groups","{\"groupname\":\""+groupName+"\"}",token);

            log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                    put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                    put(LogMessage.ACTION, "Remove group from ServiceAccount").
                    put(LogMessage.MESSAGE, String.format ("userResponse status is [%s]", groupResp.getHttpstatus())).
                    put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                    build()));

            String responseJson="";
            List<String> policies = new ArrayList<>();
            List<String> currentpolicies = new ArrayList<>();
            String policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(access)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

            if(HttpStatus.OK.equals(groupResp.getHttpstatus())){
                responseJson = groupResp.getResponse();
                try {
                    ObjectMapper objMapper = new ObjectMapper();
                    currentpolicies = ControllerUtil.getPoliciesAsListFromJson(objMapper, responseJson);
                } catch (IOException e) {
                    log.error(e);
                    log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                            put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                            put(LogMessage.ACTION, "Remove group from ServiceAccount").
                            put(LogMessage.MESSAGE, String.format ("Exception while creating currentpolicies or groups")).
                            put(LogMessage.STACKTRACE, Arrays.toString(e.getStackTrace())).
                            put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                            build()));
                }

                policies.addAll(currentpolicies);
                policies.remove(policy);
            }
            String policiesString = org.apache.commons.lang3.StringUtils.join(policies, ",");
			String currentpoliciesString = org.apache.commons.lang3.StringUtils.join(currentpolicies, ",");
            Response ldapConfigresponse = ControllerUtil.configureLDAPGroup(groupName,policiesString,token);

            if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT) || ldapConfigresponse.getHttpstatus().equals(HttpStatus.OK)){
				String path = new StringBuffer(TVaultConstants.SVC_ACC_ROLES_PATH).append("/").append(svcAccName).toString();
				Map<String,String> params = new HashMap<String,String>();
				params.put("type", "groups");
				params.put("name",groupName);
				params.put("path",path);
				params.put("access","delete");
				Response metadataResponse = ControllerUtil.updateMetadata(params,token);
				if(metadataResponse !=null && HttpStatus.NO_CONTENT.equals(metadataResponse.getHttpstatus())){
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Remove Group to Service Account").
							put(LogMessage.MESSAGE, "Group configuration Success.").
							put(LogMessage.STATUS, metadataResponse.getHttpstatus().toString()).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Group is successfully removed from Service Account\"]}");
				}
				ldapConfigresponse = ControllerUtil.configureLDAPGroup(groupName,currentpoliciesString,token);
				if(ldapConfigresponse.getHttpstatus().equals(HttpStatus.NO_CONTENT)){
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to Service Account").
							put(LogMessage.MESSAGE, "Reverting, group policy update success").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Group configuration failed.Please try again\"]}");
				}else{
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Group to Service Account").
							put(LogMessage.MESSAGE, "Reverting group policy update failed").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Group configuration failed.Contact Admin \"]}");
				}
            }
            else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to remove the group from the Service Account\"]}");
            }
        }
        else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Access denied: No permission to add groups to this service account\"]}");
        }

    }

    /**
     * Associate Approle to Service Account
     * @param userDetails
     * @param token
     * @param serviceAccountApprole
     * @return
     */
    public ResponseEntity<String> associateApproletoSvcAcc(UserDetails userDetails, String token, ServiceAccountApprole serviceAccountApprole) {
        log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                put(LogMessage.ACTION, "Add Approle to Service Account").
                put(LogMessage.MESSAGE, String.format ("Trying to add Approle to Service Account")).
                put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                build()));

        if(!ControllerUtil.areSvcaccApproleInputsValid(serviceAccountApprole)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Invalid input values\"]}");
        }

        String approleName = serviceAccountApprole.getApprolename();
        String svcAccName = serviceAccountApprole.getSvcAccName();
        String access = serviceAccountApprole.getAccess();

        if (TVaultConstants.USERPASS.equals(vaultAuthMethod)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"This operation is not supported for Userpass authentication. \"]}");
        }
        if (serviceAccountApprole.getApprolename().equals(TVaultConstants.SELF_SERVICE_APPROLE_NAME)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Access denied: no permission to associate this AppRole to any Service Account\"]}");
        }
        approleName = (approleName !=null) ? approleName.toLowerCase() : approleName;
        access = (access != null) ? access.toLowerCase(): access;

        boolean isAuthorized = hasAddOrRemovePermission(userDetails, svcAccName, token);
        if(isAuthorized){

            String policy = TVaultConstants.EMPTY;
            policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(access)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

            log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                    put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                    put(LogMessage.ACTION, "Add Approle to Service Account").
                    put(LogMessage.MESSAGE, String.format ("policy is [%s]", policy)).
                    put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                    build()));
            String r_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.READ_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String w_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.WRITE_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String d_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.DENY_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();
            String o_policy = new StringBuffer().append(TVaultConstants.SVC_ACC_POLICIES_PREFIXES.getKey(TVaultConstants.SUDO_POLICY)).append(TVaultConstants.SVC_ACC_PATH_PREFIX).append("_").append(svcAccName).toString();

            log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                    put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                    put(LogMessage.ACTION, "Add Approle to Service Account").
                    put(LogMessage.MESSAGE, String.format ("Policies are, read - [%s], write - [%s], deny -[%s], owner - [%s]", r_policy, w_policy, d_policy, o_policy)).
                    put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                    build()));

            Response roleResponse = reqProcessor.process("/auth/approle/role/read","{\"role_name\":\""+approleName+"\"}",token);

            log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
                    put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
                    put(LogMessage.ACTION, "Add Approle to ServiceAccount").
                    put(LogMessage.MESSAGE, String.format ("roleResponse status is [%s]", roleResponse.getHttpstatus())).
                    put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
                    build()));

            String responseJson="";
            List<String> policies = new ArrayList<>();
            List<String> currentpolicies = new ArrayList<>();

            if(HttpStatus.OK.equals(roleResponse.getHttpstatus())) {
				responseJson = roleResponse.getResponse();
				ObjectMapper objMapper = new ObjectMapper();
				try {
					JsonNode policiesArry = objMapper.readTree(responseJson).get("data").get("policies");
					for (JsonNode policyNode : policiesArry) {
						currentpolicies.add(policyNode.asText());
					}
				} catch (IOException e) {
					log.error(e);
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Approle to ServiceAccount").
							put(LogMessage.MESSAGE, String.format("Exception while creating currentpolicies")).
							put(LogMessage.STACKTRACE, Arrays.toString(e.getStackTrace())).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
				}
				policies.addAll(currentpolicies);
				policies.remove(r_policy);
				policies.remove(w_policy);
				policies.remove(d_policy);
				policies.add(policy);
			}
			String policiesString = org.apache.commons.lang3.StringUtils.join(policies, ",");
			String currentpoliciesString = org.apache.commons.lang3.StringUtils.join(currentpolicies, ",");

			log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
					put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
					put(LogMessage.ACTION, "Add Approle to ServiceAccount").
					put(LogMessage.MESSAGE, String.format ("policies [%s] before calling configureApprole", policies)).
					put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
					build()));

			Response approleControllerResp = ControllerUtil.configureApprole(approleName,policiesString,token);

			if(approleControllerResp.getHttpstatus().equals(HttpStatus.NO_CONTENT) || approleControllerResp.getHttpstatus().equals(HttpStatus.OK)){
				String path = new StringBuffer(TVaultConstants.SVC_ACC_ROLES_PATH).append("/").append(svcAccName).toString();
				Map<String,String> params = new HashMap<String,String>();
				params.put("type", "app-roles");
				params.put("name",approleName);
				params.put("path",path);
				params.put("access",access);
				Response metadataResponse = ControllerUtil.updateMetadata(params,token);
				if(metadataResponse !=null && HttpStatus.NO_CONTENT.equals(metadataResponse.getHttpstatus())){
					log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Approle to Service Account").
							put(LogMessage.MESSAGE, "Approle configuration Success.").
							put(LogMessage.STATUS, metadataResponse.getHttpstatus().toString()).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.OK).body("{\"messages\":[\"Approle successfully associated with Service Account\"]}");
				}
				approleControllerResp = ControllerUtil.configureApprole(approleName,currentpoliciesString,token);
				if(approleControllerResp.getHttpstatus().equals(HttpStatus.NO_CONTENT)){
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Approle to Service Account").
							put(LogMessage.MESSAGE, "Reverting, Approle policy update success").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Approle configuration failed.Please try again\"]}");
				}else{
					log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
							put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
							put(LogMessage.ACTION, "Add Approle to Service Account").
							put(LogMessage.MESSAGE, "Reverting Approle policy update failed").
							put(LogMessage.RESPONSE, (null!=metadataResponse)?metadataResponse.getResponse():TVaultConstants.EMPTY).
							put(LogMessage.STATUS, (null!=metadataResponse)?metadataResponse.getHttpstatus().toString():TVaultConstants.EMPTY).
							put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
							build()));
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"errors\":[\"Approle configuration failed.Contact Admin \"]}");
				}
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Failed to add Approle to the Service Account\"]}");
			}
        }else{
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"errors\":[\"Access denied: No permission to add Approle to this service account\"]}");
        }
    }

	public ResponseEntity<String> getServiceAccountMeta(String token, UserDetails userDetails, String path) {
		log.debug(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
				put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
				put(LogMessage.ACTION, "Get metadata for Service Account").
				put(LogMessage.MESSAGE, String.format ("Trying to get metadata for Service Account")).
				put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
				build()));
		if (path != null && path.startsWith("/")) {
			path = path.substring(1, path.length());
		}
		if (path != null && path.endsWith("/")) {
			path = path.substring(0, path.length()-1);
		}
		String _path = "metadata/"+path;
		Response response = reqProcessor.process("/sdb","{\"path\":\""+_path+"\"}",token);
		return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
	}
}