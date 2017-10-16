/**
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
package org.apache.knox.gateway.service.knoxsso;

import org.apache.knox.gateway.util.RegExUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.security.token.JWTokenAuthority;
import org.apache.knox.gateway.services.security.token.TokenServiceException;
import org.apache.knox.gateway.services.security.token.impl.JWT;
import org.apache.knox.gateway.services.security.token.impl.JWTToken;
import org.apache.knox.gateway.util.RegExUtils;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;

/**
 * Some tests for the Knox SSO service.
 */
public class WebSSOResourceTest {

  protected static RSAPublicKey publicKey;
  protected static RSAPrivateKey privateKey;

  @BeforeClass
  public static void setup() throws Exception, NoSuchAlgorithmException {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(1024);
    KeyPair KPair = kpg.generateKeyPair();

    publicKey = (RSAPublicKey) KPair.getPublic();
    privateKey = (RSAPrivateKey) KPair.getPrivate();
  }

  @Test
  public void testWhitelistMatching() throws Exception {
    String whitelist = "^https?://.*example.com:8080/.*$;" +
        "^https?://.*example.com/.*$;" +
        "^https?://.*example2.com:\\d{0,9}/.*$;" +
        "^https://.*example3.com:\\d{0,9}/.*$;" +
        "^https?://localhost:\\d{0,9}/.*$;^/.*$";

    // match on explicit hostname/domain and port
    Assert.assertTrue("Failed to match whitelist", RegExUtils.checkWhitelist(whitelist,
        "http://host.example.com:8080/"));
    // match on non-required port
    Assert.assertTrue("Failed to match whitelist", RegExUtils.checkWhitelist(whitelist,
        "http://host.example.com/"));
    // match on required but any port
    Assert.assertTrue("Failed to match whitelist", RegExUtils.checkWhitelist(whitelist,
        "http://host.example2.com:1234/"));
    // fail on missing port
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example2.com/"));
    // fail on invalid port
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example.com:8081/"));
    // fail on alphanumeric port
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example.com:A080/"));
    // fail on invalid hostname/domain
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example.net:8080/"));
    // fail on required port
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example2.com/"));
    // fail on required https
    Assert.assertFalse("Matched whitelist inappropriately", RegExUtils.checkWhitelist(whitelist,
        "http://host.example3.com/"));
    // match on localhost and port
    Assert.assertTrue("Failed to match whitelist", RegExUtils.checkWhitelist(whitelist,
        "http://localhost:8080/"));
    // match on local/relative path
    Assert.assertTrue("Failed to match whitelist", RegExUtils.checkWhitelist(whitelist,
        "/local/resource/"));
  }

  @Test
  public void testGetToken() throws Exception {

    ServletContext context = EasyMock.createNiceMock(ServletContext.class);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.name")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.secure.only")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.max.age")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.domain.suffix")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.redirect.whitelist.regex")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.token.audiences")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.token.ttl")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.enable.session")).andReturn(null);

    HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
    EasyMock.expect(request.getParameter("originalUrl")).andReturn("http://localhost:9080/service");
    EasyMock.expect(request.getParameterMap()).andReturn(Collections.<String,String[]>emptyMap());
    EasyMock.expect(request.getServletContext()).andReturn(context).anyTimes();

    Principal principal = EasyMock.createNiceMock(Principal.class);
    EasyMock.expect(principal.getName()).andReturn("alice").anyTimes();
    EasyMock.expect(request.getUserPrincipal()).andReturn(principal).anyTimes();

    GatewayServices services = EasyMock.createNiceMock(GatewayServices.class);
    EasyMock.expect(context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE)).andReturn(services);

    JWTokenAuthority authority = new TestJWTokenAuthority(publicKey, privateKey);
    EasyMock.expect(services.getService(GatewayServices.TOKEN_SERVICE)).andReturn(authority);

    HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
    ServletOutputStream outputStream = EasyMock.createNiceMock(ServletOutputStream.class);
    CookieResponseWrapper responseWrapper = new CookieResponseWrapper(response, outputStream);

    EasyMock.replay(principal, services, context, request);

    WebSSOResource webSSOResponse = new WebSSOResource();
    webSSOResponse.request = request;
    webSSOResponse.response = responseWrapper;
    webSSOResponse.context = context;
    webSSOResponse.init();

    // Issue a token
    webSSOResponse.doGet();

    // Check the cookie
    Cookie cookie = responseWrapper.getCookie("hadoop-jwt");
    assertNotNull(cookie);

    JWTToken parsedToken = new JWTToken(cookie.getValue());
    assertEquals("alice", parsedToken.getSubject());
    assertTrue(authority.verifyToken(parsedToken));
  }

  @Test
  public void testAudiences() throws Exception {

    ServletContext context = EasyMock.createNiceMock(ServletContext.class);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.name")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.secure.only")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.max.age")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.domain.suffix")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.redirect.whitelist.regex")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.token.audiences")).andReturn("recipient1,recipient2");
    EasyMock.expect(context.getInitParameter("knoxsso.token.ttl")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.enable.session")).andReturn(null);

    HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
    EasyMock.expect(request.getParameter("originalUrl")).andReturn("http://localhost:9080/service");
    EasyMock.expect(request.getParameterMap()).andReturn(Collections.<String,String[]>emptyMap());
    EasyMock.expect(request.getServletContext()).andReturn(context).anyTimes();

    Principal principal = EasyMock.createNiceMock(Principal.class);
    EasyMock.expect(principal.getName()).andReturn("alice").anyTimes();
    EasyMock.expect(request.getUserPrincipal()).andReturn(principal).anyTimes();

    GatewayServices services = EasyMock.createNiceMock(GatewayServices.class);
    EasyMock.expect(context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE)).andReturn(services);

    JWTokenAuthority authority = new TestJWTokenAuthority(publicKey, privateKey);
    EasyMock.expect(services.getService(GatewayServices.TOKEN_SERVICE)).andReturn(authority);

    HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
    ServletOutputStream outputStream = EasyMock.createNiceMock(ServletOutputStream.class);
    CookieResponseWrapper responseWrapper = new CookieResponseWrapper(response, outputStream);

    EasyMock.replay(principal, services, context, request);

    WebSSOResource webSSOResponse = new WebSSOResource();
    webSSOResponse.request = request;
    webSSOResponse.response = responseWrapper;
    webSSOResponse.context = context;
    webSSOResponse.init();

    // Issue a token
    webSSOResponse.doGet();

    // Check the cookie
    Cookie cookie = responseWrapper.getCookie("hadoop-jwt");
    assertNotNull(cookie);

    JWTToken parsedToken = new JWTToken(cookie.getValue());
    assertEquals("alice", parsedToken.getSubject());
    assertTrue(authority.verifyToken(parsedToken));

    // Verify the audiences
    List<String> audiences = Arrays.asList(parsedToken.getAudienceClaims());
    assertEquals(2, audiences.size());
    assertTrue(audiences.contains("recipient1"));
    assertTrue(audiences.contains("recipient2"));
  }

  @Test
  public void testAudiencesWhitespace() throws Exception {

    ServletContext context = EasyMock.createNiceMock(ServletContext.class);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.name")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.secure.only")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.max.age")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.cookie.domain.suffix")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.redirect.whitelist.regex")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.token.audiences")).andReturn(" recipient1, recipient2 ");
    EasyMock.expect(context.getInitParameter("knoxsso.token.ttl")).andReturn(null);
    EasyMock.expect(context.getInitParameter("knoxsso.enable.session")).andReturn(null);

    HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
    EasyMock.expect(request.getParameter("originalUrl")).andReturn("http://localhost:9080/service");
    EasyMock.expect(request.getParameterMap()).andReturn(Collections.<String,String[]>emptyMap());
    EasyMock.expect(request.getServletContext()).andReturn(context).anyTimes();

    Principal principal = EasyMock.createNiceMock(Principal.class);
    EasyMock.expect(principal.getName()).andReturn("alice").anyTimes();
    EasyMock.expect(request.getUserPrincipal()).andReturn(principal).anyTimes();

    GatewayServices services = EasyMock.createNiceMock(GatewayServices.class);
    EasyMock.expect(context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE)).andReturn(services);

    JWTokenAuthority authority = new TestJWTokenAuthority(publicKey, privateKey);
    EasyMock.expect(services.getService(GatewayServices.TOKEN_SERVICE)).andReturn(authority);

    HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
    ServletOutputStream outputStream = EasyMock.createNiceMock(ServletOutputStream.class);
    CookieResponseWrapper responseWrapper = new CookieResponseWrapper(response, outputStream);

    EasyMock.replay(principal, services, context, request);

    WebSSOResource webSSOResponse = new WebSSOResource();
    webSSOResponse.request = request;
    webSSOResponse.response = responseWrapper;
    webSSOResponse.context = context;
    webSSOResponse.init();

    // Issue a token
    webSSOResponse.doGet();

    // Check the cookie
    Cookie cookie = responseWrapper.getCookie("hadoop-jwt");
    assertNotNull(cookie);

    JWTToken parsedToken = new JWTToken(cookie.getValue());
    assertEquals("alice", parsedToken.getSubject());
    assertTrue(authority.verifyToken(parsedToken));

    // Verify the audiences
    List<String> audiences = Arrays.asList(parsedToken.getAudienceClaims());
    assertEquals(2, audiences.size());
    assertTrue(audiences.contains("recipient1"));
    assertTrue(audiences.contains("recipient2"));
  }

  /**
   * A wrapper for HttpServletResponseWrapper to store the cookies
   */
  private static class CookieResponseWrapper extends HttpServletResponseWrapper {

    private ServletOutputStream outputStream;
    private Map<String, Cookie> cookies = new HashMap<>();

    public CookieResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    public CookieResponseWrapper(HttpServletResponse response, ServletOutputStream outputStream) {
        super(response);
        this.outputStream = outputStream;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public void addCookie(Cookie cookie) {
        super.addCookie(cookie);
        cookies.put(cookie.getName(), cookie);
    }

    public Cookie getCookie(String name) {
        return cookies.get(name);
    }

  }

  private static class TestJWTokenAuthority implements JWTokenAuthority {

    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;

    public TestJWTokenAuthority(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
      this.publicKey = publicKey;
      this.privateKey = privateKey;
    }

    @Override
    public JWT issueToken(Subject subject, String algorithm)
      throws TokenServiceException {
      Principal p = (Principal) subject.getPrincipals().toArray()[0];
      return issueToken(p, algorithm);
    }

    @Override
    public JWT issueToken(Principal p, String algorithm)
      throws TokenServiceException {
      return issueToken(p, null, algorithm);
    }

    @Override
    public JWT issueToken(Principal p, String audience, String algorithm)
      throws TokenServiceException {
      return issueToken(p, audience, algorithm, -1);
    }

    @Override
    public boolean verifyToken(JWT token) throws TokenServiceException {
      JWSVerifier verifier = new RSASSAVerifier(publicKey);
      return token.verify(verifier);
    }

    @Override
    public JWT issueToken(Principal p, String audience, String algorithm,
                               long expires) throws TokenServiceException {
      List<String> audiences = null;
      if (audience != null) {
        audiences = new ArrayList<String>();
        audiences.add(audience);
      }
      return issueToken(p, audiences, algorithm, expires);
    }

    @Override
    public JWT issueToken(Principal p, List<String> audiences, String algorithm,
                               long expires) throws TokenServiceException {
      String[] claimArray = new String[4];
      claimArray[0] = "KNOXSSO";
      claimArray[1] = p.getName();
      claimArray[2] = null;
      if (expires == -1) {
        claimArray[3] = null;
      } else {
        claimArray[3] = String.valueOf(expires);
      }

      JWTToken token = null;
      if ("RS256".equals(algorithm)) {
        token = new JWTToken("RS256", claimArray, audiences);
        JWSSigner signer = new RSASSASigner(privateKey);
        token.sign(signer);
      } else {
        throw new TokenServiceException("Cannot issue token - Unsupported algorithm");
      }

      return token;
    }

    @Override
    public JWT issueToken(Principal p, String algorithm, long expiry)
        throws TokenServiceException {
      return issueToken(p, Collections.<String>emptyList(), algorithm, expiry);
    }

    @Override
    public boolean verifyToken(JWT token, RSAPublicKey publicKey) throws TokenServiceException {
      JWSVerifier verifier = new RSASSAVerifier(publicKey);
      return token.verify(verifier);
    }

  }

}
