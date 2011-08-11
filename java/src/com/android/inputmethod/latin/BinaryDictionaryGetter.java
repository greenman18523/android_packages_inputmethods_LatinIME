/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helper class to get the address of a mmap'able dictionary file.
 */
class BinaryDictionaryGetter {

    /**
     * Used for Log actions from this class
     */
    private static final String TAG = BinaryDictionaryGetter.class.getSimpleName();

    // Prevents this from being instantiated
    private BinaryDictionaryGetter() {}

    /**
     * Escapes a string for any characters that may be suspicious for a file or directory name.
     *
     * Concretely this does a sort of URL-encoding except it will encode everything that's not
     * alphanumeric or underscore. (true URL-encoding leaves alone characters like '*', which
     * we cannot allow here)
     */
    // TODO: create a unit test for this method
    private static String replaceFileNameDangerousCharacters(String name) {
        // This assumes '%' is fully available as a non-separator, normal
        // character in a file name. This is probably true for all file systems.
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); ++i) {
            final int codePoint = name.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint) || '_' == codePoint) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append('%');
                sb.append(Integer.toHexString(codePoint));
            }
        }
        return sb.toString();
    }

    /**
     * Find out the cache directory associated with a specific locale.
     */
    private static String getCacheDirectoryForLocale(Locale locale, Context context) {
        final String relativeDirectoryName = replaceFileNameDangerousCharacters(locale.toString());
        final String absoluteDirectoryName = context.getFilesDir() + File.separator
                + relativeDirectoryName;
        final File directory = new File(absoluteDirectoryName);
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Log.e(TAG, "Could not create the directory for locale" + locale);
            }
        }
        return absoluteDirectoryName;
    }

    /**
     * Generates a file name for the id and locale passed as an argument.
     *
     * In the current implementation the file name returned will always be unique for
     * any id/locale pair, but please do not expect that the id can be the same for
     * different dictionaries with different locales. An id should be unique for any
     * dictionary.
     * The file name is pretty much an URL-encoded version of the id inside a directory
     * named like the locale, except it will also escape characters that look dangerous
     * to some file systems.
     * @param id the id of the dictionary for which to get a file name
     * @param locale the locale for which to get the file name
     * @param context the context to use for getting the directory
     * @return the name of the file to be created
     */
    public static String getCacheFileName(String id, Locale locale, Context context) {
        final String fileName = replaceFileNameDangerousCharacters(id);
        return getCacheDirectoryForLocale(locale, context) + File.separator + fileName;
    }

    /**
     * Returns a file address from a resource, or null if it cannot be opened.
     */
    private static AssetFileAddress loadFallbackResource(final Context context,
            final int fallbackResId, final Locale locale) {
        final Resources res = context.getResources();
        final Locale savedLocale = Utils.setSystemLocale(res, locale);
        final AssetFileDescriptor afd = res.openRawResourceFd(fallbackResId);
        Utils.setSystemLocale(res, savedLocale);

        if (afd == null) {
            Log.e(TAG, "Found the resource but cannot read it. Is it compressed? resId="
                    + fallbackResId);
            return null;
        }
        return AssetFileAddress.makeFromFileNameAndOffset(
                context.getApplicationInfo().sourceDir, afd.getStartOffset(), afd.getLength());
    }

    /**
     * Returns the list of cached files for a specific locale.
     *
     * @param locale the locale to find the dictionary files for.
     * @param context the context on which to open the files upon.
     * @return a list of binary dictionary files, which may be null but may not be empty.
     */
    private static List<AssetFileAddress> getCachedDictionaryList(final Locale locale,
            final Context context) {
        final String directoryName = getCacheDirectoryForLocale(locale, context);
        final File[] cacheFiles = new File(directoryName).listFiles();
        if (null == cacheFiles) return null;

        final ArrayList<AssetFileAddress> fileList = new ArrayList<AssetFileAddress>();
        for (File f : cacheFiles) {
            if (f.canRead()) {
                fileList.add(AssetFileAddress.makeFromFileName(f.getPath()));
            } else {
                Log.e(TAG, "Found a cached dictionary file but cannot read it");
            }
        }
        return fileList.size() > 0 ? fileList : null;
    }

    /**
     * Returns a list of file addresses for a given locale, trying relevant methods in order.
     *
     * Tries to get binary dictionaries from various sources, in order:
     * - Uses a content provider to get a public dictionary set, as per the protocol described
     *   in BinaryDictionaryFileDumper.
     * If that fails:
     * - Gets a file name from the fallback resource passed as an argument.
     * If that fails:
     * - Returns null.
     * @return The address of a valid file, or null.
     */
    public static List<AssetFileAddress> getDictionaryFiles(final Locale locale,
            final Context context, final int fallbackResId) {
        try {
            // cacheDictionariesFromContentProvider returns the list of files it copied to local
            // storage, but we don't really care about what was copied NOW: what we want is the
            // list of everything we ever cached, so we ignore the return value.
            BinaryDictionaryFileDumper.cacheDictionariesFromContentProvider(locale, context);
            List<AssetFileAddress> cachedDictionaryList = getCachedDictionaryList(locale, context);
            if (null != cachedDictionaryList) {
                return cachedDictionaryList;
            }
            // If the list is null, fall through and return the fallback
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Unable to create dictionary file from provider for locale "
                    + locale.toString() + ": falling back to internal dictionary");
        } catch (IOException e) {
            Log.e(TAG, "Unable to read source data for locale "
                    + locale.toString() + ": falling back to internal dictionary");
        }
        final AssetFileAddress fallbackAsset = loadFallbackResource(context, fallbackResId,
                locale);
        if (null == fallbackAsset) return null;
        return Arrays.asList(fallbackAsset);
    }
}
