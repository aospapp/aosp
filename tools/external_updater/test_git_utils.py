#
# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Tests for the git_utils module."""
from pathlib import Path
import pytest
from pytest_mock import MockerFixture

from git_utils import tree_uses_pore


@pytest.fixture(name="repo_tree")
def fixture_repo_tree(tmp_path: Path) -> Path:
    """Fixture for a repo tree."""
    (tmp_path / ".repo").write_text("")
    (tmp_path / "external/foobar").mkdir(parents=True)
    return tmp_path


@pytest.fixture(name="pore_tree")
def fixture_pore_tree(repo_tree: Path) -> Path:
    """Fixture for a pore tree."""
    (repo_tree / ".pore").write_text("")
    return repo_tree


def test_tree_uses_pore_fast_path(tmp_path: Path, mocker: MockerFixture) -> None:
    """Tests that the fast-path does not recurse."""
    which_mock = mocker.patch("shutil.which")
    which_mock.return_value = None
    path_parent_mock = mocker.patch("pathlib.Path.parent")
    assert not tree_uses_pore(tmp_path)
    path_parent_mock.assert_not_called()


def test_tree_uses_pore_identifies_pore_trees(pore_tree: Path, mocker: MockerFixture) -> None:
    """Tests that a pore tree is correctly identified."""
    which_mock = mocker.patch("shutil.which")
    which_mock.return_value = Path("pore")
    assert tree_uses_pore(pore_tree)


def test_tree_uses_pore_identifies_repo_trees(repo_tree: Path, mocker: MockerFixture) -> None:
    """Tests that a repo tree is correctly identified."""
    which_mock = mocker.patch("shutil.which")
    which_mock.return_value = Path("pore")
    assert not tree_uses_pore(repo_tree)
