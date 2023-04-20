package it.gov.pagopa.swclient.mil.idp.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import it.gov.pagopa.swclient.mil.bean.CommonHeader;
import it.gov.pagopa.swclient.mil.bean.Errors;
import it.gov.pagopa.swclient.mil.idp.ErrorCode;
import it.gov.pagopa.swclient.mil.idp.bean.AccessToken;
import it.gov.pagopa.swclient.mil.idp.bean.GetAccessToken;
import it.gov.pagopa.swclient.mil.idp.bean.KeyPair;
import it.gov.pagopa.swclient.mil.idp.bean.PublicKey;
import it.gov.pagopa.swclient.mil.idp.dao.GrantEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class TokenManager {

    @Inject
    TokenStringGenerator tokenStringGenerator;

    @Inject
    KeyRetriever keyRetriever;

    @Inject
    RefreshTokenStringGenerator refreshTokenStringGenerator;


    /*
     * Duration of access tokens in seconds.
     */
    @ConfigProperty(name = "access.duration")
    long accessDuration;

    /*
     * Duration of refresh tokens in seconds.
     */
    @ConfigProperty(name = "refresh.duration")
    long refreshDuration;

    @ConfigProperty(name = "issuer")
    String issuer;

    /*
     *
     */
    @ConfigProperty(name = "access.audience")
    List<String> accessAudience;

    /*
     *
     */
    @ConfigProperty(name = "refresh.audience")
    List<String> refreshAudience;

    /**
     * @param refreshToken
     * @throws NotAuthorizedException
     */
    private void verifyAlgorithm(SignedJWT refreshToken) throws NotAuthorizedException {
        Log.debug("Verify refresh token algorithm.");
        if (!Objects.equals(refreshToken.getHeader().getAlgorithm(), JWSAlgorithm.RS256)) {
            Log.warnf("[%s] Wrong refresh token algorithm. Expected %s, found %s.",
                    ErrorCode.WRONG_REFRESH_TOKEN_ALGORITHM, JWSAlgorithm.RS256,
                    refreshToken.getHeader().getAlgorithm());
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_ALGORITHM))).build());
        }
    }

    /**
     * @param refreshTokenClaimsSet
     * @throws NotAuthorizedException
     */
    private void verifyIssuer(JWTClaimsSet refreshTokenClaimsSet) throws NotAuthorizedException {
        Log.debug("Verify refresh token issuer.");
        String currentIssuer = refreshTokenClaimsSet.getIssuer();
        if (!Objects.equals(currentIssuer, issuer)) {
            Log.warnf("[%s] Wrong refresh token issuer. Expected %s, found %s.", ErrorCode.WRONG_REFRESH_TOKEN_ISSUER,
                    issuer, currentIssuer);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_ISSUER))).build());
        }
    }

    /**
     * @param refreshTokenClaimsSet
     * @throws NotAuthorizedException
     */
    private void verifyIssueTime(JWTClaimsSet refreshTokenClaimsSet) throws NotAuthorizedException {
        Log.debug("Verify refresh token issue time.");
        long threshold = new Date().getTime();
        Date issueTime = refreshTokenClaimsSet.getIssueTime();
        if (issueTime == null || issueTime.getTime() > threshold) {
            Log.warnf("[%s] Wrong issue time. Found %d but threshold is %d.", ErrorCode.WRONG_REFRESH_TOKEN_ISSUE_TIME,
                    issueTime.getTime(), threshold);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_ISSUE_TIME))).build());
        }
    }

    /**
     * @param refreshTokenClaimsSet
     * @throws NotAuthorizedException
     */
    private void verifyExpirationTime(JWTClaimsSet refreshTokenClaimsSet) throws NotAuthorizedException {
        Log.debug("Verify refresh token expiration time.");
        Date expirationTime = refreshTokenClaimsSet.getExpirationTime();
        if (expirationTime == null || expirationTime.getTime() < LocalDate.now().toEpochDay()) {
            Log.warnf("[%s] Refresh token expired.", ErrorCode.REFRESH_TOKEN_EXPIRED);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.REFRESH_TOKEN_EXPIRED))).build());
        }
    }

    /**
     * @param refreshTokenClaimsSet
     * @throws NotAuthorizedException
     */
    private void verifyAudience(JWTClaimsSet refreshTokenClaimsSet) throws NotAuthorizedException {
        Log.debug("Verify refresh token audience.");

        String refreshAudienceStr = Arrays
                .toString(refreshAudience.stream().sorted().collect(Collectors.toList()).toArray());

        List<String> currentRefreshAudience = refreshTokenClaimsSet.getAudience();
        if (refreshAudience == null) {
            Log.warnf("[%s] Wrong refresh token audience. Expected %s, found %s.",
                    ErrorCode.WRONG_REFRESH_TOKEN_AUDIENCE, refreshAudienceStr, refreshAudience);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_AUDIENCE))).build());
        }

        String currentRefreshAudienceStr = Arrays
                .toString(currentRefreshAudience.stream().sorted().collect(Collectors.toList()).toArray());

        if (!currentRefreshAudienceStr.equals(refreshAudienceStr)) {
            Log.warnf("[%s] Wrong refresh token audience. Expected %s, found %s.",
                    ErrorCode.WRONG_REFRESH_TOKEN_AUDIENCE, refreshAudienceStr, currentRefreshAudienceStr);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_AUDIENCE))).build());
        }
    }

    /**
     * @param refreshTokenClaimsSet
     * @throws NotAuthorizedException
     */
    private void verifyScope(JWTClaimsSet refreshTokenClaimsSet) throws NotAuthorizedException {
        Log.debug("Verify refresh token scope.");
        Object scope = refreshTokenClaimsSet.getClaim("scope");
        if (!Objects.equals(scope, "offline_access")) {
            Log.warnf("[%s] Wrong refresh token scope. Expected %s, found %s.", ErrorCode.WRONG_REFRESH_TOKEN_SCOPE,
                    "offline_access", scope);
            throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new Errors(List.of(ErrorCode.WRONG_REFRESH_TOKEN_SCOPE))).build());
        }
    }

    private RSAPublicKey getPublicKey(PublicKey publicKey) throws NoSuchAlgorithmException, InvalidKeySpecException {
        BigInteger modulus = Base64URL.from(publicKey.getN()).decodeToBigInteger();
        BigInteger exponent = Base64URL.from(publicKey.getE()).decodeToBigInteger();
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) (factory.generatePublic(spec));
    }

    /**
     * @param keyPair
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     */
    public PrivateKey getPrivateKey(KeyPair keyPair) throws NoSuchAlgorithmException, InvalidKeySpecException {
        BigInteger modulus = Base64URL.from(keyPair.getN()).decodeToBigInteger();
        BigInteger privateExponent = Base64URL.from(keyPair.getD()).decodeToBigInteger();
        RSAPrivateKeySpec spec = new RSAPrivateKeySpec(modulus, privateExponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(spec);
    }

    /**
     * @param refreshToken
     * @return
     */
    private Uni<Void> verifySignature(SignedJWT refreshToken, KeyRetriever keyRetriever) {
        return keyRetriever.getPublicKey(refreshToken.getHeader().getKeyID()).onItem()
                .transform(key -> key.orElseThrow(() -> {
                    Log.warnf("[%s] Key %s not found.", ErrorCode.KEY_NOT_FOUND, refreshToken.getHeader().getKeyID());
                    return new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                            .entity(new Errors(List.of(ErrorCode.KEY_NOT_FOUND))).build());
                })).chain(key -> {
                    try {
                        JWSVerifier verifier = new RSASSAVerifier(getPublicKey(key));
                        boolean signatureOk = refreshToken.verify(verifier);
                        if (signatureOk) {
                            return Uni.createFrom().voidItem();
                        } else {
                            return Uni.createFrom()
                                    .failure(new InternalServerErrorException(Response.status(Response.Status.UNAUTHORIZED)
                                            .entity(new Errors(List.of(ErrorCode.WRONG_SIGNATURE))).build()));
                        }
                    } catch (JOSEException | NoSuchAlgorithmException | InvalidKeySpecException e) {
                        Log.errorf(e, "[%s] Error while signature verification.",
                                ErrorCode.ERROR_WHILE_SIGNATURE_VERIFICATION);
                        return Uni.createFrom()
                                .failure(new InternalServerErrorException(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                        .entity(new Errors(List.of(ErrorCode.ERROR_WHILE_SIGNATURE_VERIFICATION)))
                                        .build()));
                    }
                });
    }

    public Uni<AccessToken> generateAccessToken(CommonHeader commonHeader, GetAccessToken getAccessToken,
                                                 GrantEntity grantEntity) {
        Log.debug("Retrieve key pair.");
        return keyRetriever.getKeyPair().chain(key -> {
            JWSSigner signer;
            try {
                signer = new RSASSASigner(getPrivateKey(key));

                JWSHeader header = new JWSHeader(JWSAlgorithm.RS256, null, null, null, null, null, null, null, null,
                        null, key.getKid(), true, null, null);
                SignedJWT accessToken = tokenStringGenerator.generateAccessToken(header, key, getAccessToken,
                        commonHeader, grantEntity, signer);
                AccessToken token;
                Log.debug(getAccessToken.getScope());
                if(getAccessToken.getScope() == null)
                {
                    Log.debug("Skip generating refresh token...");
                    token = new AccessToken(accessToken.serialize(), accessDuration);
                }
                else if(getAccessToken.getScope().equals("offline_access"))
                {
                    Log.debug("Generating refresh token...");
                    String refreshTokenStr = refreshTokenStringGenerator.generateRefreshTokenString(commonHeader,
                            getAccessToken, header, signer);
                    token = new AccessToken(accessToken.serialize(), refreshTokenStr, accessDuration);
                }
                else
                {
                    throw new InternalServerErrorException(); //TODO: find the right exception to throw
                }

                Log.debug("Tokens generated successfully.");
                return Uni.createFrom().item(token);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                return Uni.createFrom().failure(e);
            }
        });
    }


    /**
     * @param refreshTokenStr
     * @return
     */
    public Uni<Void> verifyRefreshToken(String refreshTokenStr) {
        try {
            SignedJWT refreshToken = SignedJWT.parse(refreshTokenStr);
            JWTClaimsSet refreshTokenClaimsSet = refreshToken.getJWTClaimsSet();
            verifyAlgorithm(refreshToken);
            verifyIssuer(refreshTokenClaimsSet);
            verifyIssueTime(refreshTokenClaimsSet);
            verifyExpirationTime(refreshTokenClaimsSet);
            verifyAudience(refreshTokenClaimsSet);
            verifyScope(refreshTokenClaimsSet);
            return verifySignature(refreshToken, keyRetriever);
        } catch (ParseException e) {
            Log.errorf(e, "[%s] Error while parsing token.", ErrorCode.ERROR_PARSING_TOKEN);
            return Uni.createFrom()
                    .failure(new InternalServerErrorException(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(new Errors(List.of(ErrorCode.ERROR_PARSING_TOKEN))).build()));
        } catch (NotAuthorizedException e) {
            return Uni.createFrom().failure(e);
        }
    }

}
