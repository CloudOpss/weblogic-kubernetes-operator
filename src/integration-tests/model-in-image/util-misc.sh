# Copyright (c) 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

function timestamp() {
  date --utc '+%Y-%m-%dT%H:%M:%S'
}

function trace() {
  echo @@ "[$(timestamp)]" "[$(basename $0):${BASH_LINENO[0]}]:" "$@"
}

function tracen() {
  echo -n @@ "[$(timestamp)]" "[$(basename $0):${BASH_LINENO[0]}]:" "$@"
}

# diefast
#
# Use 'diefast' to touch the '/tmp/diefast' file in all WL pods. This will
# cause them to use an unsafe (fast) shutdown when they're deleted/rolled.

function diefast() {
  kubectl -n ${DOMAIN_NAMESPACE:-sample-domain1-ns} get pods -l weblogic.serverName \
    -o=jsonpath='{range .items[*]}{.metadata.namespace}{" "}{.metadata.name}{"\n"}' \
    | awk '{ system("set -x ; kubectl -n " $1 " exec " $2 " touch /tmp/diefast") }'
}


# testapp
#
# Use 'testapp internal|traefik somestring' to invoke the test
# WebLogic cluster's jsp app and check that the given string is
# is reflected there-in.
#
#   "internal" invokes curl on the admin server
#   "traefik" invokes curl locally through the traefik node port
#
# For example, 'testapp internal "Hello World!"'.

function testapp() {
  (
  set +e
  set -u

  if [ "$1" = "internal" ]; then
    local cluster_name=cluster-1
    local cluster_service_name=${DOMAIN_UID:-sample-domain1}-cluster-${cluster_name}
    local cluster_service_name=$(tr [A-Z_] [a-z-] <<< $cluster_service_name)
    local admin_name=admin-server
    local admin_service_name=${DOMAIN_UID:-sample-domain1}-${admin_name}
    local admin_service_name=$(tr [A-Z_] [a-z-] <<< $admin_service_name)
    local ns=${DOMAIN_NAMESPACE:-sample-domain1-ns}
    local command="kubectl exec -n $ns $admin_service_name -- bash -c \"curl -s -S -m 5 http://$cluster_service_name:8001/sample_war/index.jsp\""

  elif [ "$1" = "traefik" ]; then
    local kube_host=$(kubectl cluster-info | grep KubeDNS | sed 's;^.*//;;' | sed 's;:.*$;;')
    local command="curl -s -S -m 5 http://$kube_host:30305/sample_war/index.jsp"

  else
    echo "@@ Error: Unexpected value for '$1' - must be 'traefik' or 'internal'"

  fi

  echo -n "@@ Info: Searching for '$2' in '$command'."

  local result=$(bash -c "$command" |& grep -c "$2")

  if [ ! "$result" = "1" ]; then
    echo
    echo "@@ Error: '$2' not found in app response:"
    bash -c "$command"
    exit 1
  else
    echo ".. Success!"
    exit 0
  fi
  )
}


# doCommand
#
#  runs the given command, potentially redirecting it to a file
#
#  if DRY_RUN is set to 'true'
#    - echos command to stdout prepended with 'dryrun: '
#
#  if DRY_RUN is not set to 'true'
#    - if first parameter is -c
#      - shift, and runs command "$@" in foreground
#    - if first parameter is ! -c
#      - redirects dommand "$@" stdout/stderr to a file something like
#        '$WORKDIR/command_out/$PPID.$COUNT.$(timestamp).$(basename $(printf $1)).out'
#      - prints out an Info with the name of the command and the location of this file
#      - follows info with 'dots' while it waits for command to complete
#    - if command fails, prints out an Error and exits non-zero
#    - if command succeeds, exits zero
#
#  This function expects -e, -u, and -o pipefail to be set. If not, it returns 1.
#

function doCommand() {
  if [ "${SHELLOPTS/errexit}" = "${SHELLOPTS}" ]; then
    trace "Error: The doCommand script expects that 'set -e' was already called."
    return 1
  fi
  if [ "${SHELLOPTS/pipefail}" = "${SHELLOPTS}" ]; then
    trace "Error: The doCommand script expects that 'set -o pipefail' was already called."
    return 1
  fi
  if [ "${SHELLOPTS/nounset}" = "${SHELLOPTS}" ]; then
    trace "Error: The doCommand script expects that 'set -u' was already called."
    return 1
  fi

  local redirect=true
  if [ "${1:-}" = "-c" ]; then
    redirect=false 
    shift
  fi

  local command="$@"

  if [ "${DRY_RUN:-}" = "true" ]; then
    echo "dryrun: $command"
    return
  fi

  if [ "$redirect" = "false" ]; then
    trace "Info: Running command '$command':"
    eval $command
    return $?
  fi

  COMMAND_OUTFILE_COUNT=${COMMAND_OUTFILE_COUNT:=0}
  COMMAND_OUTFILE_COUNT=$((COMMAND_OUTFILE_COUNT + 1))

  mkdir -p $WORKDIR/command_out

  local out_file="$WORKDIR/command_out/$PPID.$(printf "%3.3u" $COMMAND_OUTFILE_COUNT).$(timestamp).$(basename $(printf $1)).out"
  tracen Info: Running command "'$command'," "output='$out_file'."
  printdots_start

  set +e
  eval $command > $out_file 2>&1
  local err_code=$?
  if [ $err_code -ne 0 ]; then
    echo
    trace "Error: Error running command '$command', output='$out_file'. Output contains:"
    cat $out_file
    trace "Error: Error running command '$command', output='$out_file'. Output dumped above."
  fi
  printdots_end
  set -e

  return $err_code
}