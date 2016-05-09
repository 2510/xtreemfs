#!/bin/sh -e

BASEDIR=`cd ../.. ; pwd`
OURDIR=`cd . ; pwd`

DESTDIR=${OURDIR}/install-root
PACKAGE_DIR=${OURDIR}/package
PACKAGE_NAME=xtreemfs-1.5.1
PACKAGE=${PACKAGE_DIR}/${PACKAGE_NAME}.txz
MANIFEST=${OURDIR}/freebsd.manifest

pkg install bash gmake openjdk8 apache-ant python27 cmake boost-all fusefs-libs

mkdir -p "${DESTDIR}"
export JAVA_HOME=/usr/local/openjdk8

(
    cd ${BASEDIR}
    gmake ANT_BIN=/usr/local/bin/ant PYTHON=python2.7 CC=cc CXX=c++ DESTDIR="${DESTDIR}" install
)

/usr/local/bin/python2.7 "${OURDIR}/make-manifest.py" --root "${DESTDIR}" --uid root --gid wheel > "${MANIFEST}"

(
    cd "${DESTDIR}"
    pkg create -o "${PACKAGE_DIR}" -M "${MANIFEST}" -r . "${PACKAGE_NAME}"
)
