package com.catpuppyapp.puppygit.utils.compare

import com.catpuppyapp.puppygit.utils.compare.param.CompareParam
import com.catpuppyapp.puppygit.utils.compare.result.IndexModifyResult
import com.catpuppyapp.puppygit.utils.compare.result.IndexStringPart
import com.catpuppyapp.puppygit.utils.compare.search.Search
import com.catpuppyapp.puppygit.utils.compare.search.SearchDirection
import com.catpuppyapp.puppygit.utils.iterator.NoCopyIterator

class SimilarCompareImpl: SimilarCompare {
    override fun<T> doCompare(
        add: CompareParam<T>,
        del: CompareParam<T>,
        emptyAsMatch:Boolean,
        emptyAsModified:Boolean,
        onlyLineSeparatorAsEmpty:Boolean,
        searchDirection: SearchDirection,
        requireBetterMatching: Boolean,
        search: Search,
        betterSearch: Search,
        matchByWords:Boolean,
        ignoreEndOfNewLine:Boolean
    ): IndexModifyResult {
        // empty check
        if(add.isEmpty() || del.isEmpty() || ((onlyLineSeparatorAsEmpty || ignoreEndOfNewLine) && (add.isOnlyLineSeparator() || del.isOnlyLineSeparator()))){ //其中一个为空或只有换行符，不比较，直接返回结果，当作无匹配
            return IndexModifyResult(matched = emptyAsMatch, matchedByReverseSearch = false,
                listOf(IndexStringPart(0, add.getLen(), emptyAsModified)),
                listOf(IndexStringPart(0, del.getLen(), emptyAsModified)))
        }

        //忽略末尾 \n
        val addWillUse = if(ignoreEndOfNewLine) {
            add.getTextNoEndOfNewLine()
        }else {
            add
        }

        val delWillUse = if(ignoreEndOfNewLine) {
            del.getTextNoEndOfNewLine()
        }else {
            del
        }

        var result:IndexModifyResult? = null

            // match by words
        if(matchByWords) {
            //如果按单词比较为真，尝试以单词比较，如果有匹配，直接返回，如果无匹配，则继续往下执行普通比较
            result = doMatchByWords(addWillUse, delWillUse, requireBetterMatching)
        }

        // if match by words not enabled or not matched, try match by chars
        if(result == null || result.matched.not()) {
            // match by chars
            val reverse = searchDirection == SearchDirection.REVERSE || searchDirection == SearchDirection.REVERSE_FIRST

            val reverseMatchIfNeed = searchDirection == SearchDirection.REVERSE_FIRST || searchDirection == SearchDirection.FORWARD_FIRST

            //用On算法最坏的情况是正向匹配一次，然后逆向匹配一次，时间复杂度为 O(2n)，正常情况时间复杂度为O(n)，O(nm)若匹配两次，也会翻倍，最坏时间复杂度变成O(2nm)
            result = if(requireBetterMatching) {
                betterSearch.doSearch(addWillUse, delWillUse, reverse)
            }else {
                search.doSearch(addWillUse, delWillUse, reverse)
            }

            //反向查找，如果需要的话。判断条件是：如果 “允许反向匹配 且 正向没匹配到”
            if(reverseMatchIfNeed && !result.matched) {
                result = if(requireBetterMatching) betterSearch.doSearch(addWillUse, delWillUse, !reverse) else search.doSearch(addWillUse, delWillUse, !reverse)
            }

        }

        // add newline back to result
        if(ignoreEndOfNewLine) {
            if (add.hasEndOfNewLine()) {
                val addList = result.add as MutableList
                addList.add(
                    IndexStringPart(
                        start = add.getLen()-1,
                        end = add.getLen(),
                        modified = del.hasEndOfNewLine().not()
                    )
                )
            }

            if (del.hasEndOfNewLine()) {
                val delList = result.del as MutableList
                delList.add(
                    IndexStringPart(
                        start = del.getLen()-1,
                        end = del.getLen(),
                        modified = add.hasEndOfNewLine().not()
                    )
                )

            }
        }

        return result

    }

    /**
     * @param requireBetterMatching if true, will try index of for not-matched words
     */
    private fun<T> doMatchByWords(
        add: CompareParam<T>,
        del: CompareParam<T>,
        requireBetterMatching: Boolean
    ):IndexModifyResult {
        val addWordAndIndexList = getWordAndIndexList(add) as MutableList
        val delWordAndIndexList = getWordAndIndexList(del) as MutableList

        val addIter = NoCopyIterator(srcList = addWordAndIndexList)
        val delIter = NoCopyIterator(srcList = delWordAndIndexList)

        var matched = false

        // save result which will return
        val addIndexResultList = mutableListOf<IndexStringPart>()
        val delIndexResultList = mutableListOf<IndexStringPart>()

//        val addNotMatchedList = mutableListOf<WordAndIndex>()
//        val delNotMatchedList = mutableListOf<WordAndIndex>()
//        addNotMatchedList.addAll(addWordAndIndexList)
//        delNotMatchedList.addAll(delWordAndIndexList)

        // index equals this, will remove
//        val removeMark = -1

//        var delMatchedCount = 0
//        val delAllCount = delWordAndIndexList.size

        while(addIter.hasNext()) {
            val addWord = addIter.next()
            val addStr = addWord.getWordStr()

            delIter.reset()
            while(delIter.hasNext()) {
                val delWord = delIter.next()
                val delStr = delWord.getWordStr()

                if(addStr == delStr) {
                    addIter.remove()
                    delIter.remove()

                    matched = true

                    addWord.matched = true
                    delWord.matched = true

                    // because is addStr equals delStr, so the addStr.length should equals delStr.length
                    addIndexResultList.add(
                        IndexStringPart(
                            start = addWord.index,
                            end = addWord.index+addStr.length,
                            modified = false
                        )
                    )

                    delIndexResultList.add(
                        IndexStringPart(
                            start = delWord.index,
                            end = delWord.index+delStr.length,
                            modified = false
                        )
                    )

                    break
                }
            }

            if(delIter.srcIsEmpty()) {
                break
            }

        }

        //if requireBetterMatching is true, try use indexOf matching the not-matched items
        if(requireBetterMatching && addIter.srcIsEmpty().not() && delIter.srcIsEmpty().not()) {
            addIter.reset()
            while(addIter.hasNext()) {
                val a = addIter.next()
                val addStr = a.getWordStr()

                delIter.reset()
                while(delIter.hasNext()) {
                    val d = delIter.next()
                    val delStr = d.getWordStr()

                    // should try `bigLengthStr.indexOf(smallLengthStr)`
                    // 应该用长的indexOf短的，所以这里有的大于判断
                    if(addStr.length > delStr.length) {
                        val indexOf = addStr.indexOf(delStr)
                        if(indexOf != -1) {
                            addIter.remove()
                            delIter.remove()

                            matched = true

                            a.matched = true
                            d.matched = true

                            val aStartIndex = a.index+indexOf
                            val aEndIndex = aStartIndex+delStr.length
                            addIndexResultList.add(
                                IndexStringPart(
                                    start = aStartIndex,
                                    end = aEndIndex,
                                    modified = false
                                )
                            )

                            delIndexResultList.add(
                                IndexStringPart(
                                    start = d.index,
                                    end = d.index+delStr.length,
                                    modified = false
                                )
                            )

                            val beforeMatched = addStr.substring(0, indexOf)
                            val afterMatched = addStr.substring(indexOf+delStr.length)
                            if(beforeMatched.isNotEmpty()) {
                                addIndexResultList.add(
                                    IndexStringPart(
                                        start = a.index,
                                        end = a.index + beforeMatched.length,
                                        modified = true
                                    )
                                )
                            }
                            if(afterMatched.isNotEmpty()) {
                                addIndexResultList.add(
                                    IndexStringPart(
                                        start = aEndIndex,
                                        end = aEndIndex + afterMatched.length,
                                        modified = true
                                    )
                                )
                            }

                            break
                        }
                    }else {
                        val indexOf = delStr.indexOf(addStr)
                        if(indexOf != -1) {
                            addIter.remove()
                            delIter.remove()

                            matched = true

                            a.matched = true
                            d.matched = true

                            addIndexResultList.add(
                                IndexStringPart(
                                    start = a.index,
                                    end = a.index+addStr.length,
                                    modified = false
                                )
                            )

                            val dStartIndex = d.index+indexOf
                            val dEndIndex = dStartIndex+addStr.length
                            delIndexResultList.add(
                                IndexStringPart(
                                    start = dStartIndex,
                                    end = dEndIndex,
                                    modified = false
                                )
                            )

                            val beforeMatched = delStr.substring(0, indexOf)
                            val afterMatched = delStr.substring(indexOf+addStr.length)
                            if(beforeMatched.isNotEmpty()) {
                                delIndexResultList.add(
                                    IndexStringPart(
                                        start = d.index,
                                        end = d.index + beforeMatched.length,
                                        modified = true
                                    )
                                )
                            }
                            if(afterMatched.isNotEmpty()) {
                                delIndexResultList.add(
                                    IndexStringPart(
                                        start = dEndIndex,
                                        end = dEndIndex + afterMatched.length,
                                        modified = true
                                    )
                                )
                            }

                            break
                        }
                    }

                }


                if(delIter.srcIsEmpty()) {
                    break
                }

            }

        }



        // when reached here, all matched items already added in the `addIndexResultList` and `delIndexResultList`,
        //  and not-matched items in the `addStillNotMatchedList` and `delStillNotMatchedList`
        //  and the order is not sorted yet

        // add not matched items, these items not matched by equals nor by indexOf, they are really `modified` items

        // at last, may still have not matched items, they are `modified`
        addIter.reset()
        while (addIter.hasNext()) {
            val item = addIter.next()
            addIndexResultList.add(
                IndexStringPart(
                    start = item.index,
                    end = item.index + item.getWordStr().length,
                    modified = true
                )
            )
        }

        delIter.reset()
        while (delIter.hasNext()) {
            val item = delIter.next()
            delIndexResultList.add(
                IndexStringPart(
                    start = item.index,
                    end = item.index + item.getWordStr().length,
                    modified = true
                )
            )
        }


        // sortedWith return new List and keep origin list unchanged, sortWith sort in place, here no need new list, so using sortWith
        addIndexResultList.sortWith(comparator)
        delIndexResultList.sortWith(comparator)


        return IndexModifyResult(
            matched = matched,
            matchedByReverseSearch = false,
            add = addIndexResultList,
            del = delIndexResultList
        )
    }

    private fun<T> getWordAndIndexList(compareParam:CompareParam<T>):List<WordAndIndex> {
        var wordMatching = false
        var spaceMatching = false
        var wordAndIndex:WordAndIndex? = null
        var spaceAndIndex:WordAndIndex? = null


        val wordAndIndexList= mutableListOf<WordAndIndex>()
        val spaceAndIndexList = mutableListOf<WordAndIndex>()

        for(i in 0 until compareParam.getLen()) {
            val char = compareParam.getChar(i)
            if(char.isWhitespace()) {
                wordMatching = false

                if(spaceMatching) {
                    spaceAndIndex!!.word.append(char)
                }else {
                    spaceAndIndex = WordAndIndex(index = i)
                    spaceAndIndex.word.append(char)
                    spaceAndIndexList.add(spaceAndIndex)
                    spaceMatching = true
                }
            }else {  // not a blank char
                spaceMatching = false

                if(wordMatching) {
                    wordAndIndex!!.word.append(char)
                }else {
                    wordAndIndex = WordAndIndex(index = i)
                    wordAndIndex.word.append(char)
                    wordAndIndexList.add(wordAndIndex)
                    wordMatching = true
                }
            }
        }

        wordAndIndexList.addAll(spaceAndIndexList)

        return wordAndIndexList
    }


}

private data class WordAndIndex(
    val index:Int=0,
    val word:StringBuilder=StringBuilder(),

    // full match(by equals) or part match(by indexof)
    //完整匹配或部分匹配，只要匹配过，就为true
    var matched:Boolean=false,
) {
    private var wordStrCached:String? = null

    fun getWordStr():String {
        if(wordStrCached==null) {
            wordStrCached = word.toString()
        }

        return wordStrCached!!
    }

}

// create comparator for sort list by index
// p.s. list compare will not remove same elements
private val comparator = { o1:IndexStringPart, o2:IndexStringPart ->
    o1.start.compareTo(o2.start)
}
