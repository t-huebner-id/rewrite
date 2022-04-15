/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.openrewrite.Tree.randomId
import org.openrewrite.internal.lang.Nullable
import org.openrewrite.marker.Markers
import org.openrewrite.marker.RecipesThatMadeChanges
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextVisitor
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class RecipeLifecycleTest {

    @Test
    fun panic() {
        val visited = AtomicInteger(0)
        val ctx = InMemoryExecutionContext()
        ctx.putMessage(Recipe.PANIC, true)

        object : Recipe() {
            override fun getDisplayName(): String = "Slow"

            override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
                return object : TreeVisitor<Tree, ExecutionContext>() {
                    override fun visit(tree: Tree, p: ExecutionContext): Tree {
                        visited.incrementAndGet()
                        return tree
                    }
                }
            }
        }.run(listOf(PlainText(randomId(), Paths.get("test.txt"), Markers.EMPTY, "hello world")), ctx)

        assertThat(visited.get()).isEqualTo(0)
    }

    @Test
    fun notApplicableRecipe() {
        val results = object : Recipe() {
            override fun getName() = "test.NotApplicable"
            override fun getDisplayName(): String {
                return name
            }

            override fun getApplicableTest(): TreeVisitor<*, ExecutionContext>? {
                return NOOP // never going to be applicable
            }

            override fun visit(before: List<SourceFile>, ctx: ExecutionContext) =
                before + PlainText(randomId(), Paths.get("test.txt"), Markers.EMPTY, "test")
        }.run(emptyList())

        assertThat(results).isEmpty()
    }

    @Test
    fun generateFile() {
        val results = object : Recipe() {
            override fun getName() = "test.GeneratingRecipe"
            override fun getDisplayName(): String {
                return name
            }

            override fun visit(before: List<SourceFile>, ctx: ExecutionContext) =
                before + PlainText(randomId(), Paths.get("test.txt"), Markers.EMPTY, "test")
        }.run(emptyList())

        assertThat(results.map { it.recipesThatMadeChanges.map { r -> r.name }.first() }
            .distinct()).containsExactly("test.GeneratingRecipe")
    }

    @Test
    fun deleteFile() {
        val results = object : Recipe() {
            override fun getName() = "test.DeletingRecipe"

            override fun getDisplayName(): String {
                return name
            }

            override fun visit(before: List<SourceFile>, ctx: ExecutionContext) =
                emptyList<SourceFile>()
        }.run(listOf(PlainText(randomId(), Paths.get("test.txt"), Markers.EMPTY, "test")))

        assertThat(results.map {
            it.recipesThatMadeChanges.map { r -> r.name }.first()
        }).containsExactly("test.DeletingRecipe")
    }

    @Test
    fun deleteFileByReturningNullFromVisit() {
        val results = object : Recipe() {
            override fun getName() = "test.DeletingRecipe"

            override fun getDisplayName(): String {
                return name
            }

            override fun getVisitor(): PlainTextVisitor<ExecutionContext> {
                return object : PlainTextVisitor<ExecutionContext>() {
                    override fun visit(tree: Tree?, p: ExecutionContext): PlainText? = null
                }

            }

        }.run(listOf(PlainText(randomId(), Paths.get("test.txt"), Markers.EMPTY, "test")))

        assertThat(results.map {
            it.recipesThatMadeChanges.map { r -> r.name }.first()
        }).containsExactly("test.DeletingRecipe")
    }

    @Suppress("USELESS_IS_CHECK")
    class FooVisitor<P> : TreeVisitor<FooSource, P>() {

        override fun preVisit(tree: FooSource, p: P): FooSource {
            if (tree !is FooSource) {
                throw RuntimeException("tree is not a FooSource")
            }
            return tree
        }

        override fun postVisit(tree: FooSource, p: P): FooSource {
            if (tree !is FooSource) {
                throw RuntimeException("tree is not a FooSource")
            }
            return tree
        }
    }

    class FooSource : SourceFile {
        override fun <P : Any?> isAcceptable(v: TreeVisitor<*, P>, p: P) = v is FooVisitor

        override fun getMarkers(): Markers = throw NotImplementedError()
        override fun <T : SourceFile?> withMarkers(markers: Markers): T = throw NotImplementedError()
        override fun getId(): UUID = throw NotImplementedError()
        override fun <T : Tree?> withId(id: UUID): T = throw NotImplementedError()
        override fun getSourcePath() = throw NotImplementedError()
        override fun withSourcePath(path: Path): SourceFile = throw NotImplementedError()
    }

    // https://github.com/openrewrite/rewrite/issues/389
    @Test
    fun sourceFilesAcceptOnlyApplicableVisitors() {
        val sources = listOf(FooSource(), PlainText(randomId(), Paths.get("test.txt"), Markers.build(listOf()), "Hello"))
        val fooVisitor = FooVisitor<ExecutionContext>()
        val textVisitor = PlainTextVisitor<ExecutionContext>()
        val ctx = InMemoryExecutionContext {
            throw it
        }
        sources.forEach {
            fooVisitor.visit(it, ctx)
            textVisitor.visit(it, ctx)
        }
    }

    @Test
    fun accurateReportingOfRecipesMakingChanges() {
        val sources = listOf(PlainText(randomId(), Paths.get("test.txt"), Markers.build(listOf()), "Hello"))
        // Set up a composite recipe which prepends "Change1" and appends "Change2" to the input text
        val recipe = object : Recipe() {
            override fun getDisplayName() = "root"
        }.apply {
            doNext(testRecipe("Change1"))
            doNext(noChangeRecipe())
            doNext(testRecipe("Change2"))
        }
        val results = recipe.run(sources, InMemoryExecutionContext { throw it })
        assertThat(results.size)
            .isEqualTo(1)
        assertThat(results.first().recipesThatMadeChanges.map { it.name })
            .containsExactlyInAnyOrder("Change1", "Change2")
    }


    /**
     * EC
     * A - B - C
     * A - B - D
     * E - F
     * E - NoChange
     * H
     * B - A - C
     *
     * Converted to a List of the following descriptors:
     *
     * A
     * |-B
     * | |-C
     * | |-D
     *
     * E
     * |-F
     *
     */
    @Test
    fun recipeDescriptorsReturnCorrectStructure() {
        val sources = listOf(
            PlainText(randomId(), Paths.get("test.txt"), Markers.build(listOf()), "Hello")
        )
        // Set up a composite recipe which with a nested structure of recipes
        val recipe = object : Recipe() {
            override fun getDisplayName() = "Environment.Composite"
            override fun getName() = displayName
            override fun toString() = displayName
        }.apply {
            doNext(testRecipe("A")
                .doNext(testRecipe("B")
                    .doNext(testRecipe("C"))))
            doNext(testRecipe("A")
                .doNext(testRecipe("B")
                    .doNext(testRecipe("D")))
                    .doNext(noChangeRecipe()))
            doNext(testRecipe("E")
                .doNext(testRecipe("F")))
            doNext(noChangeRecipe())
        }
        val results = recipe.run(sources, InMemoryExecutionContext { throw it })
        assertThat(results.size).isEqualTo(1)

        val recipesThatMadeChanges =
            results.first().after?.markers?.findFirst(RecipesThatMadeChanges::class.java)?.orElse(null)
        assertThat(recipesThatMadeChanges).isNotNull
        val recipeDescriptors = recipesThatMadeChanges?.recipeDescriptors()
        assertThat(recipeDescriptors?.size).isEqualTo(2)

        val aDescriptor = recipeDescriptors?.get(0)
        val bDescriptor = aDescriptor?.recipeList?.get(0);
        // B has three child recipes one is no change
        assertThat(bDescriptor?.name).isEqualTo("B")
        assertThat(bDescriptor?.recipeList?.size).isEqualTo(2)
    }

    private fun testRecipe(name: String): Recipe {
        return object : Recipe() {
            override fun getDisplayName() = name
            override fun getName() = displayName
            override fun toString() = displayName
            override fun getVisitor(): PlainTextVisitor<ExecutionContext> {
                return object : PlainTextVisitor<ExecutionContext>() {
                    override fun visit(@Nullable tree: Tree?, p: ExecutionContext): PlainText {
                        var pt = tree as PlainText
                        if (!pt.printAll().contains(displayName)) {
                            pt = pt.withText(displayName + pt.printAll())
                        }
                        return pt
                    }
                }
            }
        }
    }
    private fun noChangeRecipe(): Recipe {
        return object : Recipe() {
            override fun getDisplayName() = "NoChange"
            override fun getName() = displayName
            override fun toString() = displayName
            override fun getVisitor(): PlainTextVisitor<ExecutionContext> {
                return object : PlainTextVisitor<ExecutionContext>() {
                    override fun visit(tree: Tree, p: ExecutionContext) = tree as PlainText
                }

            }
        }
    }
}
