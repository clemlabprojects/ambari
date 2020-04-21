#!/bin/bash

supervisord -c /etc/supervisord.conf
export PS1='[\u@\h : \w]\$ '
/bin/bash