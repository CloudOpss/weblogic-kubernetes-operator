#!/bin/bash
# Copyright (c) 2019, 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

#
# This is an example of how to configure a model-in-image domain resource.
#
# This script creates 'WORKDIR/DOMAIN_RESOURCE_FILENAME'
# from template './DOMAIN_RESOURCE_TEMPLATE'.
#
# Warning!!
#
#    'WORKDIR/DOMAIN_RESOURCE_FILENAME' is overwritten if it already
#     exists, and the old file is copied into 
#    'WORKDIR/$(dirname $DOMAIN_RESOURCE_FILENAME)/mii-domain-saved/'.
#
# Optional environment variables (see ./README for details):
#
#   WORKDIR, CUSTOM_DOMAIN_NAME, DOMAIN_UID, DOMAIN_NAMESPACE
#   MODEL_IMAGE_NAME, MODEL_IMAGE_TAG
#   WDT_DOMAIN_TYPE
#   INCLUDE_MODEL_CONFIGMAP
#   DOMAIN_RESOURCE_FILENAME, DOMAIN_RESOURCE_TEMPLATE
#

set -eu
set -o pipefail

SCRIPTDIR="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
source $SCRIPTDIR/env-init.sh

for var in DOMAIN_UID \
           DOMAIN_NAMESPACE \
           CUSTOM_DOMAIN_NAME \
           WDT_DOMAIN_TYPE \
           MODEL_IMAGE_NAME \
           MODEL_IMAGE_TAG \
           INCLUDE_MODEL_CONFIGMAP \
           DOMAIN_RESOURCE_FILENAME \
           DOMAIN_RESOURCE_TEMPLATE
do
  echo "@@ Info: ${var}=${!var}"
done

function timestamp() {
  date --utc '+%Y-%m-%dT%H:%M:%S'
}

mkdir -p $(dirname $WORKDIR/$DOMAIN_RESOURCE_FILENAME)

echo "@@"
echo "@@ Info: Creating domain resource file 'WORKDIR/DOMAIN_RESOURCE_FILENAME' from 'SCRIPTDIR/DOMAIN_RESOURCE_TEMPLATE'"

if [ -e "$WORKDIR/$DOMAIN_RESOURCE_FILENAME" ]; then
  save_file=$(dirname $DOMAIN_RESOURCE_FILENAME)/mii-domain-saved/$(basename $DOMAIN_RESOURCEFILENAME).$(timestamp)
  echo "@@"
  echo "@@ Notice! An old version of the domain resource file already exists and will be replaced."
  echo "@@ Notice! Saving old version of the domain resource file to 'WORKDIR/${save_file}'"
  mkdir -p "$WORKDIR/$(dirname $save_file)"
  cp "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"  "$WORKDIR/${save_file}"
fi

echo "@@"

cp "$SCRIPTDIR/$DOMAIN_RESOURCE_TEMPLATE" "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"

for template_var in WDT_DOMAIN_TYPE \
                    CUSTOM_DOMAIN_NAME \
                    DOMAIN_UID \
                    DOMAIN_NAMESPACE \
                    MODEL_IMAGE_NAME \
                    MODEL_IMAGE_TAG
do
  sed -i -e "s;@@${template_var}@@;${!template_var};" "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"
done


if [ "${INCLUDE_MODEL_CONFIGMAP}" = "true" ]; then
  # we're going to deploy and use the model.configuration.configMap, and this
  # configmap depends on the datasource-secret.
  sed -i -e "s;\#\(configMap:\);\1;"           "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"
  sed -i -e "s;\#\(secrets:\);\1;"             "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"
  sed -i -e "s;\#\(-.*datasource-secret\);\1;" "$WORKDIR/$DOMAIN_RESOURCE_FILENAME"
fi

echo "@@ Info: Done."