# Libabigail Docker

Libabigail comes with two Dockerfile in [docker](docker) to build each of:

 - a Fedora base image (recommended)
 - an Ubuntu base image.

These containers are built and deployed on merges to the main branch and releases.

### Usage

Here is how to build the containers. Note that we build so it belongs to the same
namespace as the repository here. "ghcr.io" means "GitHub Container Registry" and
is the [GitHub packages](https://github.com/features/packages) registry that supports
 Docker images and other OCI artifacts.

```bash
$ docker build -f docker/Dockerfile.fedora -t ghcr.io/woodard/libabigail-fedora .
```
```bash
$ docker build -f docker/Dockerfile.ubuntu -t ghcr.io/woodard/libabigail-ubuntu-22.04 .
```

Note that currently the fedora image is deployed to `ghcr.io/woodard/libabigail:latest`.

### Shell

To shell into a container (here is an example with ubuntu):

```bash
$ docker run -it ghcr.io/woodard/libabigail-ubuntu-22.04 bash
```

Off the bat, you can find the abi executables:

```bash
# which abidiff
/opt/abigail-env/.spack-env/view/bin/abidiff
```

Since the ubuntu base uses spack, you can interact with spack.
You can go to the environment in `/opt/abigail-env` and (given you
have the source code bound to `/src`) build and test again.

```bash
$ spack install
```

And that's it! This workflow should make it easy to install development versions of libabigail with spack.
We will also use the "production" containers to grab libraries in:

```
$ ls /opt/abigail-env/.spack-env/view/
bin  include  lib  share
```

Note that the fedora container does not come with spack.

### Testing

We provide a testing container, which will use a fedora base and add new code to
compile, and then run `make check`. You can do this as follows on your local machine:

```bash
$ docker build -f docker/Dockerfile.test -t test .
```
