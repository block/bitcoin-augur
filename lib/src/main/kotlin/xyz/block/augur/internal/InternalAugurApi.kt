/*
 * Copyright (c) 2025 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.block.augur.internal

import kotlin.RequiresOptIn

/**
 * Marks declarations that are internal to the Augur library.
 *
 * These APIs are not considered stable and may change without notice.
 * They should not be used by consumers of the library.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This API is internal to Augur and should not be used by client code."
)
public annotation class InternalAugurApi
