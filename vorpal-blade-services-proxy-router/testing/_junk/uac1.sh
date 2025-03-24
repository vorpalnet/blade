#!/bin/bash

export SERVICE=18165551234
export DURATION=5000
export OCCAS_HOST=occas
export OCCAS_PORT=5060
export UAS_HOST=occas
export UAS_PORT=5099

#sipp -d ${DURATION} -rsa ${OCCAS_HOST}:${OCCAS_PORT} -m 1 -s ${SERVICE} -sf uac.xml ${UAS_HOST}:${UAS_PORT}

sipp -d ${DURATION} -m 1 -s ${SERVICE} -sf uac.xml ${OCCAS_HOST}:${OCCAS_PORT} -trace_err -trace_msg

