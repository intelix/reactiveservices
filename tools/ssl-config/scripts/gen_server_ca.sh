#!/bin/bash

mkdir -p gen

export PW=`cat gen/password`

# Create a self signed key pair root CA certificate.
keytool -genkeypair -v \
  -alias serverca \
  -dname "CN=serverCA, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore gen/server-ca.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

# Export the exampleCA public certificate as exampleca.crt so that it can be used in trust stores.
keytool -export -v \
  -alias serverca \
  -file gen/server-ca.crt \
  -keypass:env PW \
  -storepass:env PW \
  -keystore gen/server-ca.jks \
  -rfc