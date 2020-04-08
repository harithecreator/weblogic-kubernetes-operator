#!/bin/bash

# TBD add doc at top of this script
#     turn parms into -e (count) and -t seconds
# TBD modify to wait for 'ready' state

set -eu
set -o pipefail

DOMAIN_UID=${DOMAIN_UID:-sample-domain1}
DOMAIN_NAMESPACE=${DOMAIN_NAMESPACE:-${DOMAIN_UID}-ns}

expected=${1:-0}
timeout_secs=${2:-240}

cur_pods=0
reported=0

while [ 1 -eq 1 ]; do

  # WL Server pods are the only pods with the weblogic.serverName label

  cur_pods=$(kubectl -n ${DOMAIN_NAMESPACE} get pods \
             -l weblogic.serverName,weblogic.domainUID=${DOMAIN_UID} \
             -o=jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
             | wc -l)

  out_str="for WebLogic pod count to reach '$expected'" 
  out_str+=", ns=$DOMAIN_NAMESPACE"
  out_str+=", domainUID=$DOMAIN_UID"
  out_str+=", timeout_secs='$timeout_secs'"
  out_str+=", cur_pods='$cur_pods'"
  out_str+=", cur_seconds='$SECONDS'"

  if [ $reported -eq 0 ]; then
    echo -n "@@ Info: Waiting $out_str:"
    reported=1
  else
    echo -n " $cur_pods"
  fi

  if [ $cur_pods -eq $expected ]; then
    echo ". Total seconds=$SECONDS."
    exit 0
  fi

  if [ $SECONDS -ge $timeout_secs ]; then
    echo
    echo "@@ Error: Timeout waiting $out_str."
    exit 1
  fi

  sleep 3
done
