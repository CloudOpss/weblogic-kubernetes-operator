#!/bin/bash
# Copyright (c) 2019, 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

#
# This script stages wdt model files from './sample-model-configmap' 
# to 'WORKDIR/model-configmap' for future inclusion in a Kubernetes
# configmap that is optionally referenced by the  domain resource
# 'configuration.model.configMap' field. By default,
# the './create-model-configmap.sh' uses 'WORKDIR/model-configmap'.
#
# Optionally set these environment variables:
#
#    WORKDIR
#      Working directory for the sample with at least 10g of space.
#      Defaults to '/tmp/$USER/model-in-image-sample-work-dir'.
#
# CUSTOMIZATION NOTE:
#
#   If you want to specify your own model files for a config map, then
#   you don't need to run this script. See MODEL_CONFIGMAP_DIR in 
#   'create-model-configmap.sh'
#

# TBD have a '-force/always' option? Default to 'when-missing'?

set -eu
set -o pipefail

SCRIPTDIR="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
source $SCRIPTDIR/env-init.sh

echo "@@"
echo "@@ Info: Staging model configmap files from SCRIPTDIR/sample-model-configmap to WORKDIR/model-configmap"
echo "@@"

mkdir -p $WORKDIR/model-configmap
rm -f $WORKDIR/model-configmap/*
cp $SCRIPTDIR/sample-model-configmap/* $WORKDIR/model-configmap