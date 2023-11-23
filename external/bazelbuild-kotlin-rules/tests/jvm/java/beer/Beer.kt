/*
 * * Copyright 2022 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package beer

import dagger.Binds
import dagger.Component
import dagger.Lazy
import dagger.Module
import dagger.Provides
import javax.inject.Inject
import javax.inject.Singleton

interface Grains {
  val name: String
}

interface Hops {
  val name: String
}

interface Yeast {
  val name: String
}

interface Kettle {
  fun steep()
  fun heat()
  fun boil()
  fun cool()
  fun ferment()
  fun addGrains(grains: Grains)
  fun addHops(hops: Hops)
  fun addYeast(yeast: Yeast)
  val isBoiling: Boolean
  val hasCooled: Boolean
  val isDone: Boolean
}

interface Storage {
  fun store()
}

class PilsnerMalt
@Inject constructor() : Grains {
  override val name get() = "Pilsner Malt"
}

class Spalt
@Inject constructor() : Hops {
  override val name get() = "Spalt"
}

class Wyeast
@Inject constructor() : Yeast {
  override val name get() = "Wyeast 2565 Kölsch"
}

class BrewPot : Kettle {
  var boiling: Boolean = false
  var cool: Boolean = true
  var done: Boolean = false

  override fun addGrains(grains: Grains) {
    println("Adding grains: " + grains.name)
  }

  override fun steep() {
    println("=> Steeping")
  }

  override fun heat() {
    println("=> Heating")
    this.cool = false
  }

  override fun boil() {
    println("=> Boiling")
    this.boiling = true
  }

  override fun cool() {
    println("=> Cooling")
    this.boiling = false
    this.cool = true
  }

  override fun addHops(hops: Hops) {
    println("Adding hops: " + hops.name)
  }

  override fun ferment() {
    println("=> Fermenting")
    this.done = true
  }

  override fun addYeast(yeast: Yeast) {
    println("Adding yeast: " + yeast.name)
  }

  override val isBoiling get() = boiling
  override val hasCooled get() = cool
  override val isDone get() = done
}

class Bottler
@Inject constructor(
  private val kettle: Kettle
) : Storage {
  override fun store() {
    if (kettle.isDone) {
      println("=> bottling")
    }
  }
}

class BeerBrewer
@Inject constructor(
  private val kettle: Lazy<Kettle>,
  private val bottler: Bottler,
  private val grains: Grains,
  private val hops: Hops,
  private val yeast: Yeast
) {
  fun brew() {
    kettle.get().apply {
      addGrains(grains)
      steep()
      heat()
      boil()
      if (isBoiling) addHops(hops)
      cool()
      if (hasCooled) addYeast(yeast)
      ferment()
    }
    bottler.store()
  }
}

@Module
abstract class CommercialEquipmentModule {
  @Binds
  abstract fun provideStorage(storage: Bottler): Storage
}

@Module(includes = [CommercialEquipmentModule::class])
class BrewingEquipmentModule {
  @Provides @Singleton
  fun provideKettle(): Kettle = BrewPot()
}

@Module
interface KolschRecipeModule {
  @Binds
  fun bindGrains(grains: PilsnerMalt): Grains

  @Binds
  fun bindHops(hops: Spalt): Hops

  @Binds
  fun bindYeast(yeast: Wyeast): Yeast
}

@Singleton
@Component(modules = [BrewingEquipmentModule::class, KolschRecipeModule::class])
interface Brewery {
  fun brewery(): BeerBrewer
}

fun main(args: Array<String>) {
  val beer = DaggerBrewery.builder().build()
  beer.brewery().brew()
}
