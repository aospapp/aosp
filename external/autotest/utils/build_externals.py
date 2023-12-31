#!/usr/bin/python2
#
# Please keep this code python 2.4 compatible and standalone.

"""
Fetch, build and install external Python library dependancies.

This fetches external python libraries, builds them using your host's
python and installs them under our own autotest/site-packages/ directory.

Usage?  Just run it.
    utils/build_externals.py
"""

import argparse
import compileall
import logging
import os
import sys

import common
from autotest_lib.client.common_lib import logging_config, logging_manager
from autotest_lib.client.common_lib import utils
from autotest_lib.utils import external_packages

# bring in site packages as well
utils.import_site_module(__file__, 'autotest_lib.utils.site_external_packages')

# Where package source be fetched to relative to the top of the autotest tree.
PACKAGE_DIR = 'ExternalSource'

# Where packages will be installed to relative to the top of the autotest tree.
INSTALL_DIR = 'site-packages'

# Installs all packages, even if the system already has the version required
INSTALL_ALL = False


# Want to add more packages to fetch, build and install?  See the class
# definitions at the end of external_packages.py for examples of how to do it.


class BuildExternalsLoggingConfig(logging_config.LoggingConfig):
    """Logging manager config."""

    def configure_logging(self, results_dir=None, verbose=False):
        """Configure logging."""
        super(BuildExternalsLoggingConfig, self).configure_logging(
                                                               use_console=True,
                                                               verbose=verbose)


def main():
    """
    Find all ExternalPackage classes defined in this file and ask them to
    fetch, build and install themselves.
    """
    options = parse_arguments(sys.argv[1:])
    logging_manager.configure_logging(BuildExternalsLoggingConfig(),
                                      verbose=True)
    os.umask(0o22)

    top_of_tree = external_packages.find_top_of_autotest_tree()
    package_dir = os.path.join(top_of_tree, PACKAGE_DIR)
    install_dir = os.path.join(top_of_tree, INSTALL_DIR)

    # Make sure the install_dir is in our python module search path
    # as well as the PYTHONPATH being used by all our setup.py
    # install subprocesses.
    if install_dir not in sys.path:
        sys.path.insert(0, install_dir)
    env_python_path_varname = 'PYTHONPATH'
    env_python_path = os.environ.get(env_python_path_varname, '')
    if install_dir+':' not in env_python_path:
        os.environ[env_python_path_varname] = ':'.join([
            install_dir, env_python_path])

    fetched_packages, fetch_errors = fetch_necessary_packages(
        package_dir, install_dir, set(options.names_to_check))
    install_errors = build_and_install_packages(fetched_packages, install_dir,
                                                options.use_chromite_main)

    # Byte compile the code after it has been installed in its final
    # location as .pyc files contain the path passed to compile_dir().
    # When printing exception tracebacks, python uses that path first to look
    # for the source code before checking the directory of the .pyc file.
    # Don't leave references to our temporary build dir in the files.
    logging.info('compiling .py files in %s to .pyc', install_dir)
    compileall.compile_dir(install_dir, quiet=True)

    # Some things install with whacky permissions, fix that.
    external_packages.system("chmod -R a+rX '%s'" % install_dir)

    errors = fetch_errors + install_errors
    for error_msg in errors:
        logging.error(error_msg)

    if not errors:
      logging.info("Syntax errors from pylint above are expected, not "
                   "problematic. SUCCESS.")
    else:
      logging.info("Problematic errors encountered. FAILURE.")
    return len(errors)


def parse_arguments(args):
    """Parse command line arguments.

    @param args: The command line arguments to parse. (ususally sys.argsv[1:])

    @returns An argparse.Namespace populated with argument values.
    """
    parser = argparse.ArgumentParser(
            description='Command to build third party dependencies required '
                        'for autotest.')
    parser.add_argument('--use_chromite_main', action='store_true',
                        help='Update chromite to main branch, rather than '
                             'prod.')
    parser.add_argument('--names_to_check', nargs='*', type=str, default=set(),
                        help='Package names to check whether they are needed '
                             'in current system.')
    return parser.parse_args(args)


def fetch_necessary_packages(dest_dir, install_dir, names_to_check=set()):
    """
    Fetches all ExternalPackages into dest_dir.

    @param dest_dir: Directory the packages should be fetched into.
    @param install_dir: Directory where packages will later installed.
    @param names_to_check: A set of package names to check whether they are
                           needed on current system. Default is empty.

    @returns A tuple containing two lists:
             * A list of ExternalPackage instances that were fetched and
               need to be installed.
             * A list of error messages for any failed fetches.
    """
    errors = []
    fetched_packages = []
    for package_class in external_packages.ExternalPackage.subclasses:
        package = package_class()
        if names_to_check and package.name.lower() not in names_to_check:
            continue
        if not package.is_needed(install_dir):
            logging.info('A new %s is not needed on this system.',
                         package.name)
            if INSTALL_ALL:
                logging.info('Installing anyways...')
            else:
                continue
        if not package.fetch(dest_dir):
            msg = 'Unable to download %s' % package.name
            logging.error(msg)
            errors.append(msg)
        else:
            fetched_packages.append(package)

    return fetched_packages, errors


def build_and_install_packages(packages, install_dir,
                               use_chromite_main=True):
    """
    Builds and installs all packages into install_dir.

    @param packages - A list of already fetched ExternalPackage instances.
    @param install_dir - Directory the packages should be installed into.
    @param use_chromite_main: True if updating chromite to main branch. Due
                                to the non-usage of origin/prod tag, the default
                                value for this argument has been set to True.
                                This argument has not been removed for backward
                                compatibility.

    @returns A list of error messages for any installs that failed.
    """
    errors = []
    for package in packages:
        if package.name.lower() == 'chromiterepo':
            if not use_chromite_main:
                logging.warning(
                    'Even though use_chromite_main has been set to %s, it '
                    'will be checked out to main as the origin/prod tag '
                    'carries little value now.', use_chromite_main)
            logging.info('Checking out autotest-chromite to main branch.')
            result = package.build_and_install(
                install_dir, main_branch=True)
        else:
            result = package.build_and_install(install_dir)
        if isinstance(result, bool):
            success = result
            message = None
        else:
            success = result[0]
            message = result[1]
        if not success:
            msg = ('Unable to build and install %s.\nError: %s' %
                   (package.name, message))
            logging.error(msg)
            errors.append(msg)
    return errors


if __name__ == '__main__':
    sys.exit(main())
