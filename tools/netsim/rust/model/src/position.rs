// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use serde::{Deserialize, Serialize};

type Centimeter = u32;

// A 3D position with coordinates in centimeters in quadrant I.
#[derive(Serialize, Deserialize, Debug)]
pub struct Position {
    x: Centimeter,
    y: Centimeter,
    z: Centimeter,
}

impl Position {
    pub fn distance(&self, other: &Self) -> Centimeter {
        f32::sqrt(
            ((self.x as i32 - other.x as i32).pow(2)
                + (self.y as i32 - other.y as i32).pow(2)
                + (self.z as i32 - other.z as i32).pow(2)) as f32,
        )
        .round() as Centimeter
    }

    pub fn new(x: Centimeter, y: Centimeter, z: Centimeter) -> Position {
        Position { x, y, z }
    }
}

impl Default for Position {
    fn default() -> Self {
        Self::new(0, 0, 0)
    }
}

impl PartialEq<Position> for Position {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        self.x == other.x && self.y == other.y && self.z == other.z
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distance_test() {
        // Example Pythagorean triples are (3, 4, 5), (5, 12, 13) ..
        let a = Position::new(0, 0, 0);
        let b = Position::new(0, 3, 4);
        assert!(Position::distance(&a, &b) == 5);
        let b = Position::new(0, 5, 12);
        assert!(Position::distance(&a, &b) == 13);
    }
    #[test]
    fn default_test() {
        let a = Position::default();
        assert!(a == Position { x: 0, y: 0, z: 0 });
    }

    #[test]
    fn position_to_json() {
        let data = Position::new(1, 2, 3);
        let data_to_str = r#"{"x":1,"y":2,"z":3}"#;
        let s = serde_json::to_string(&data).unwrap();
        assert_eq!(s, data_to_str)
    }
}
