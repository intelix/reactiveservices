#!/bin/bash

export DIR=$1
export HOST=$2

echo "Generating certs for $HOST in $DIR/$HOST"

export PW=`cat $DIR/password`

mkdir -p $DIR/$HOST


# Create a server certificate, tied to example.com
keytool -genkeypair -v \
  -alias server \
  -dname "CN=$HOST, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore $DIR/$HOST/server-keystore.jks \
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
  -keystore $DIR/$HOST/server-keystore.jks \
  -file $DIR/$HOST/server.csr

# Tell exampleCA to sign the example.com certificate. Note the extension is on the request, not the
# original certificate.
# Technically, keyUsage should be digitalSignature for DHE or ECDHE, keyEncipherment for RSA.
keytool -gencert -v \
  -alias serverca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore $DIR/server-ca.jks \
  -infile $DIR/$HOST/server.csr \
  -outfile $DIR/$HOST/server.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:$HOST" \
  -rfc

# Tell example.com.jks it can trust exampleca as a signer.
keytool -import -v \
  -alias serverca \
  -file $DIR/server-ca.crt \
  -keystore $DIR/$HOST/server-keystore.jks \
  -storetype JKS \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into example.com.jks
keytool -import -v \
  -alias server \
  -file $DIR/$HOST/server.crt \
  -keystore $DIR/$HOST/server-keystore.jks \
  -storetype JKS \
  -storepass:env PW

rm -f $DIR/$HOST/server.csr
rm -f $DIR/$HOST/server.crt

# List out the contents of example.com.jks just to confirm it.
# If you are using Play as a TLS termination point, this is the key store you should present as the server.
keytool -list -v \
  -keystore $DIR/$HOST/server-keystore.jks \
  -storepass:env PW