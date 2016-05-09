#!/usr/local/bin/python2.7

import argparse
import grp
import json
import os
import pwd
import re
import stat
import subprocess
import sys

def get_abi():
    pkg_process = subprocess.Popen(['pkg', '-vv'], stdout=subprocess.PIPE)
    abi_regex = re.compile(r'ABI\s*=\s*"(.+)"\s*;\s*')
    for line in pkg_process.stdout.readlines():
        match = abi_regex.match(line)
        if not match is None:
            abi = match.group(1)
    if abi is None:
        return
    else:
        return abi

def get_files(base_dir, uid=None, gid=None):
    manifest_files = {}
    for root, dirs, files in os.walk(base_dir):
        rel_root = os.path.relpath(root, base_dir)
        any = []
        any.extend(files)
        any.extend(dirs)
        for name in any:
            path = os.path.join(root, name)
            rel_path = os.path.join(rel_root, name)
            if rel_path.startswith('./'):
                rel_path = rel_path[1:]
            elif not rel_path.startswith('/'):
                rel_path = '/' + rel_path
            path_stat = os.lstat(path)
            manifest_files[rel_path] = {
                'uname': uid or path_stat.st_uid,
                'gname': gid or path_stat.st_gid,
                'perm': oct(stat.S_IMODE(path_stat.st_mode))
            }
    return manifest_files

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Generates MANIFEST for pkg.')
    parser.add_argument('--source-manifest', help='source MANIFEST file.', default='manifest.source')
    parser.add_argument('--root', help='make install\'ed DESTDIR', default='install-root')
    parser.add_argument('--uid', help='uid for installed files. (default: root)', default='root')
    parser.add_argument('--gid', help='gid for installed files. (default: wheel)', default='wheel')
    args = parser.parse_args()

    with open('manifest.source', 'r') as f:
        manifest = json.load(f)
    manifest['abi'] = get_abi()
    manifest['files'] = get_files(args.root, args.uid, args.gid)
    print json.dumps(manifest)
