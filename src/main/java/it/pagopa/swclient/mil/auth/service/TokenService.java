/*
 * TokenService.java
 *
 * 17 mag 2023
 */
package it.pagopa.swclient.mil.auth.service;

import static it.pagopa.swclient.mil.auth.bean.ClaimName.ACQUIRER_ID;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.CHANNEL;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.CLIENT_ID;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.GROUPS;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.MERCHANT_ID;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.SCOPE;
import static it.pagopa.swclient.mil.auth.bean.ClaimName.TERMINAL_ID;
import static it.pagopa.swclient.mil.auth.bean.GrantType.REFRESH_TOKEN;
import static it.pagopa.swclient.mil.auth.bean.Scope.OFFLINE_ACCESS;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import it.pagopa.swclient.mil.auth.bean.GetAccessTokenRequest;
import it.pagopa.swclient.mil.auth.bean.GetAccessTokenResponse;
import jakarta.inject.Inject;

/**
 * This class generates access token string and refresh token string if any and signs them.
 * 
 * @author Antonio Tarricone
 */
public abstract class TokenService {
	/*
	 * Access token duration.
	 */
	@ConfigProperty(name = "access.duration")
	long accessDuration;

	/*
	 * Duration of refresh tokens in seconds.
	 */
	@ConfigProperty(name = "refresh.duration")
	long refreshDuration;

	/*
	 * 
	 */
	@Inject
	ClientVerifier clientVerifier;

	/*
	 * 
	 */
	@Inject
	RolesFinder roleFinder;
	
	/*
	 * 
	 */
	@Inject
	TokenSigner tokenSigner;

	/**
	 * 
	 * @param strings
	 * @return
	 */
	private String concat(List<String> strings) {
		if (strings == null) {
			return null;
		}
		StringBuilder buffer = new StringBuilder();
		strings.forEach(x -> {
			buffer.append(x);
			buffer.append(" ");
		});
		return buffer.toString().trim();
	}
	
	/**
	 * 
	 * @param request
	 * @param duration
	 * @param roles
	 * @param scopes
	 * @return
	 */
	private Uni<String> generate(GetAccessTokenRequest request, long duration, List<String> roles, List<String> scopes) {
		Date now = new Date();
		JWTClaimsSet payload = new JWTClaimsSet.Builder()
			.subject(request.getClientId())
			.issueTime(now)
			.expirationTime(new Date(now.getTime() + duration * 1000))
			.claim(ACQUIRER_ID, request.getAcquirerId())
			.claim(CHANNEL, request.getChannel())
			.claim(MERCHANT_ID, request.getMerchantId())
			.claim(CLIENT_ID, request.getClientId())
			.claim(TERMINAL_ID, request.getTerminalId())
			.claim(SCOPE, concat(scopes))
			.claim(GROUPS, roles)
			.build();
		Log.debug("Token signing.");
		return tokenSigner.sign(payload).map(SignedJWT::serialize);
	}

	/**
	 * This method generates access token string and refresh token string if any and signs them.
	 * 
	 * @param request
	 * @param roles
	 * @return
	 */
	private Uni<GetAccessTokenResponse> generateToken(GetAccessTokenRequest request, List<String> roles) {
		Log.debug("Access token generation.");
		if (Objects.equals(request.getScope(), OFFLINE_ACCESS) || request.getGrantType().equals(REFRESH_TOKEN)) {
			/*
			 * With refresh token.
			 */
			return generate(request, accessDuration, roles, null)
				.chain(accessToken -> {
					Log.debug("Refresh token generation.");
					return generate(request, refreshDuration, null, List.of(OFFLINE_ACCESS))
						.map(refreshToken -> new GetAccessTokenResponse(accessToken, refreshToken, accessDuration));
				});
		} else {
			/*
			 * Without refresh token.
			 */
			return generate(request, accessDuration, roles, null)
				.map(accessToken -> new GetAccessTokenResponse(accessToken, null, accessDuration));
		}
	}

	/**
	 * This method contains all common logic behind the access token generation.
	 * 
	 * @param request
	 * @return
	 */
	public Uni<GetAccessTokenResponse> process(GetAccessTokenRequest request) {
		return clientVerifier.verify(request.getClientId(), request.getChannel(), request.getClientSecret())
			.chain(() -> roleFinder.findRoles(request.getAcquirerId(), request.getChannel(), request.getClientId(), request.getMerchantId(), request.getTerminalId()))
			.chain(roleEntity -> generateToken(request, roleEntity.getRoles()));
	}
}