package com.example.skip.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.skip.R
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackIconButton(onBack = onBack) },
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazyScreenScaffold(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackIconButton(onBack = onBack) },
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun BackIconButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back_24),
            contentDescription = "返回"
        )
    }
}

fun initialVisibleCount(totalCount: Int, batchSize: Int): Int {
    if (totalCount <= 0 || batchSize <= 0) return 0
    return min(totalCount, batchSize)
}

fun nextVisibleCount(currentCount: Int, totalCount: Int, batchSize: Int): Int {
    if (totalCount <= 0) return 0
    if (batchSize <= 0) return min(max(currentCount, 0), totalCount)
    return min(max(currentCount, 0) + batchSize, totalCount)
}

@Composable
fun AutoLoadMoreEffect(
    listState: LazyListState,
    visibleCount: Int,
    totalCount: Int,
    batchSize: Int,
    threshold: Int = 6,
    onVisibleCountChange: (Int) -> Unit
) {
    LaunchedEffect(listState, visibleCount, totalCount, batchSize, threshold) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (totalCount > visibleCount && lastVisibleIndex >= visibleCount - threshold) {
                    onVisibleCountChange(nextVisibleCount(visibleCount, totalCount, batchSize))
                }
            }
    }
}
