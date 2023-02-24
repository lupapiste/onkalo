#!/bin/bash

set -eu

lein do clean, eastwood, test2junit, uberjar
