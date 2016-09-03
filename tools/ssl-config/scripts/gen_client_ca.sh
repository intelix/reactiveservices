#!/bin/bash

export DIR=$1

mkdir -p $DIR

export PW=`cat $DIR/password`


# Create a self signed certificate & private key to create a root certificate authority.
keytool -genkeypair -v \
  -alias clientca \
  -keystore $DIR/client-ca.jks \
  -dname "CN=clientca, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

# Export the client-ca certificate from the keystore.  This goes to nginx under "ssl_client_certificate"
# and is presented in the CertificateRequest.
keytool -export -v \
  -alias clientca \
  -file $DIR/client-ca.crt \
  -storepass:env PW \
  -keystore $DIR/client-ca.jks \
  -rfc

# Export the client CA's certificate and private key to pkcs12, so it's safe.
keytool -importkeystore -v \
  -srcalias clientca \
  -srckeystore $DIR/client-ca.jks \
  -srcstorepass:env PW \
  -destkeystore $DIR/client-ca.p12 \
  -deststorepass:env PW \
  -deststoretype PKCS12