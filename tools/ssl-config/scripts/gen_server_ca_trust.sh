#!/bin/bash


# Create a JKS keystore that trusts the example CA, with the default password.
keytool -import -v \
  -alias serverca \
  -file gen/server-ca.crt \
  -storepass:env PW \
  -keystore gen/server-ca-trust.jks << EOF
yes
EOF

# List out the details of the store password.
keytool -list -v \
  -keystore gen/server-ca-trust.jks \
  -storepass:env PW

# #context