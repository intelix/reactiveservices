#!/bin/bash

export PW=`cat gen/password`


# Create another key pair that will act as the client.
keytool -genkeypair -v \
  -alias client \
  -keystore gen/client-keystore.jks \
  -dname "CN=client, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 2048

# Create a certificate signing request from the client certificate.
keytool -certreq -v \
  -alias client \
  -keypass:env PW \
  -storepass:env PW \
  -keystore gen/client-keystore.jks \
  -file gen/client.csr

# Make clientCA create a certificate chain saying that client is signed by clientCA.
keytool -gencert -v \
  -alias clientca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore gen/client-ca.jks \
  -infile gen/client.csr \
  -outfile gen/client.crt \
  -ext EKU="clientAuth" \
  -rfc

keytool -import -v \
  -alias clientca \
  -file gen/client-ca.crt \
  -keystore gen/client-keystore.jks \
  -storetype JKS \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into client.jks.  This is important, as JSSE won't send a client
# certificate if it can't find one signed by the client-ca presented in the CertificateRequest.
keytool -import -v \
  -alias client \
  -file gen/client.crt \
  -keystore gen/client-keystore.jks \
  -storetype JKS \
  -storepass:env PW






# Then, strip out the client CA alias from client.jks, just leaving the signed certificate.
#keytool -delete -v \
# -alias clientca \
# -storepass:env PW \
# -keystore client.jks

# List out the contents of client.jks just to confirm it.
keytool -list -v \
  -keystore gen/client-keystore.jks \
  -storepass:env PW