#!/bin/bash

mkdir -p gen

export PW=`cat gen/password`

# Create a server certificate, tied to example.com
keytool -genkeypair -v \
  -alias server \
  -dname "CN=localhost, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore gen/server-keystore.jks \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 2048 \
  -validity 385

# Create a certificate signing request for example.com
keytool -certreq -v \
  -alias server \
  -keypass:env PW \
  -storepass:env PW \
  -keystore gen/server-keystore.jks \
  -file gen/server.csr

# Tell exampleCA to sign the example.com certificate. Note the extension is on the request, not the
# original certificate.
# Technically, keyUsage should be digitalSignature for DHE or ECDHE, keyEncipherment for RSA.
keytool -gencert -v \
  -alias serverca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore gen/server-ca.jks \
  -infile gen/server.csr \
  -outfile gen/server.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:localhost" \
  -rfc

# Tell example.com.jks it can trust exampleca as a signer.
keytool -import -v \
  -alias serverca \
  -file gen/server-ca.crt \
  -keystore gen/server-keystore.jks \
  -storetype JKS \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into example.com.jks
keytool -import -v \
  -alias server \
  -file gen/server.crt \
  -keystore gen/server-keystore.jks \
  -storetype JKS \
  -storepass:env PW

# List out the contents of example.com.jks just to confirm it.
# If you are using Play as a TLS termination point, this is the key store you should present as the server.
keytool -list -v \
  -keystore gen/server-keystore.jks \
  -storepass:env PW