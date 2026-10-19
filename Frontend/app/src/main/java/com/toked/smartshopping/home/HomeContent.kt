package com.toked.smartshopping.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import com.toked.smartshopping.ui.theme.ShoppingGreen
import com.toked.smartshopping.ui.theme.ShoppingGreenContainer

/** UI-only destinations until their business features are connected. No sample shopping data. */
private enum class HomeTab(val label: String, val symbol: HomeSymbol) {
    HOME("Trang chủ", HomeSymbol.HOME), MAP("Bản đồ", HomeSymbol.MAP),
    HISTORY("Lịch sử", HomeSymbol.HISTORY), TEMPLATES("Mẫu", HomeSymbol.LIST),
    ACCOUNT("Cá nhân", HomeSymbol.PERSON),
}

@Composable
fun HomeContent(
    email: String,
    maxStores: Int,
    modeLabel: String,
    onProfile: () -> Unit,
    onSessions: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(email) { mutableStateOf(HomeTab.HOME) }
    var query by rememberSaveable(email) { mutableStateOf("") }
    var notice by rememberSaveable(email) { mutableStateOf<String?>(null) }
    var confirmLogout by rememberSaveable(email) { mutableStateOf(false) }
    BackHandler(tab != HomeTab.HOME) { tab = HomeTab.HOME }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HomeHeader(email, { tab = HomeTab.ACCOUNT }, { notice = "Bạn chưa có thông báo mới." })
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                HomeTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { HomeIcon(destination.symbol) },
                        label = { Text(destination.label, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (tab) {
                    HomeTab.HOME -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Tìm kế hoạch, mẫu…", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { HomeIcon(HomeSymbol.SEARCH) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton({ query = "" }) {
                                    HomeIcon(HomeSymbol.CLOSE, "Xóa tìm kiếm")
                                }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                        if (query.isNotBlank()) {
                            EmptyHomeCard(HomeSymbol.SEARCH, "Không tìm thấy kết quả",
                                "Chưa có kế hoạch hoặc mẫu đã lưu phù hợp với “${query.trim()}”.")
                        } else {
                            PlanningHero { notice = "Tạo kế hoạch mua sắm sẽ có khi tính năng lập kế hoạch được kết nối." }
                            HomeSection("Kế hoạch đang thực hiện") {
                                EmptyHomeCard(HomeSymbol.CART, "Chưa có kế hoạch mua sắm.",
                                    "Bắt đầu một kế hoạch để sắp xếp danh sách và kiểm soát ngân sách của bạn.")
                            }
                            HomeSection("Giá tốt hôm nay") {
                                EmptyHomeCard(HomeSymbol.STORE, "Chưa có thông tin giá",
                                    "Giá và ưu đãi sẽ hiển thị khi có nguồn sản phẩm được xác minh.")
                            }
                            HomeSection("Mua sắm gần đây", { tab = HomeTab.HISTORY }) {
                                EmptyHomeCard(HomeSymbol.HISTORY, "Chưa có lịch sử mua sắm",
                                    "Các lần mua sắm hoàn tất sẽ được lưu tại đây.")
                            }
                            HomeSection("Mẫu thường dùng", { tab = HomeTab.TEMPLATES }) {
                                Text("Tạo nhanh trong 1 chạm để đi chợ nhẹ nhàng hơn",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                EmptyHomeCard(HomeSymbol.LIST, "Chưa có mẫu đã lưu",
                                    "Lưu danh sách quen thuộc thành mẫu để dùng lại cho lần sau.")
                            }
                        }
                    }
                    HomeTab.ACCOUNT -> {
                        Text("Cá nhân", style = MaterialTheme.typography.headlineMedium)
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(email, style = MaterialTheme.typography.titleMedium)
                                Text("Hồ sơ mặc định", style = MaterialTheme.typography.labelLarge)
                                Text("Tối đa $maxStores cửa hàng", style = MaterialTheme.typography.bodyMedium)
                                Text("Ưu tiên: $modeLabel", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        OutlinedButton(onProfile, Modifier.fillMaxWidth()) { Text("Hồ sơ mua sắm") }
                        OutlinedButton(onSessions, Modifier.fillMaxWidth()) { Text("Phiên đăng nhập") }
                        OutlinedButton({ confirmLogout = true }, Modifier.fillMaxWidth()) { Text("Đăng xuất") }
                    }
                    else -> {
                        Text(tab.label, style = MaterialTheme.typography.headlineMedium)
                        val detail = when (tab) {
                            HomeTab.MAP -> "Bản đồ và khám phá cửa hàng sẽ có khi tính năng bản đồ được kết nối."
                            HomeTab.HISTORY -> "Các lần mua sắm hoàn tất sẽ xuất hiện khi tính năng lịch sử được kết nối."
                            else -> "Các mẫu mua sắm của bạn sẽ xuất hiện khi tính năng mẫu được kết nối."
                        }
                        EmptyHomeCard(tab.symbol, "${tab.label} chưa sẵn sàng", detail)
                        TextButton({ tab = HomeTab.HOME }) { Text("Về trang chủ") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    notice?.let { message ->
        AlertDialog(onDismissRequest = { notice = null },
            title = { Text("Smart Shopping") }, text = { Text(message) },
            confirmButton = { TextButton({ notice = null }) { Text("Đã hiểu") } })
    }
    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text("Đăng xuất?") }, text = { Text("Bạn sẽ đăng xuất khỏi thiết bị này.") },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("Hủy") } },
            confirmButton = { TextButton({ confirmLogout = false; onLogout() }) { Text("Xác nhận đăng xuất") } })
    }
}

@Composable
private fun HomeHeader(email: String, onAccount: () -> Unit, onNotifications: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalIconButton(onAccount, Modifier.size(44.dp)) { HomeIcon(HomeSymbol.PERSON, "Mở cá nhân") }
            Column(Modifier.weight(1f)) {
                Text("Xin chào 👋", style = MaterialTheme.typography.titleMedium)
                Text("Hôm nay bạn muốn mua gì?", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onNotifications) { HomeIcon(HomeSymbol.BELL, "Thông báo") }
            // Initials use the signed-in owner's email, never a prototype person's photo.
            FilledTonalIconButton(onAccount, Modifier.size(40.dp)) {
                Text(email.take(1).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PlanningHero(onCreate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(ShoppingGreen, ShoppingGreenContainer)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape) {
                    Text("⊕  Hành động chính", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                HomeIcon(HomeSymbol.CALENDAR, tint = Color.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tạo kế hoạch mua sắm", color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
                Text("Lên danh sách & kiểm soát ngân sách của bạn", color = Color.White.copy(alpha = .9f),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Button(onCreate, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerLowest, contentColor = colors.primary)) {
                Text("+", fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Text("Bắt đầu ngay", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                HomeIcon(HomeSymbol.ARROW)
            }
        }
    }
}

@Composable
private fun HomeSection(title: String, onMore: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (onMore != null) TextButton(onMore) { Text("Xem tất cả", style = MaterialTheme.typography.labelSmall) }
        }
        content()
    }
}

@Composable
private fun EmptyHomeCard(symbol: HomeSymbol, title: String, description: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) { HomeIcon(symbol, tint = MaterialTheme.colorScheme.primary) }
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class HomeSymbol(val path: String) {
    HOME("M4,4h16v4H4z M4,10h16v10H4z M9,14h6v6H9z"),
    MAP("M3,5l6,-2 6,2 6,-2v16l-6,2 -6,-2 -6,2z M9,3v16 M15,5v16"),
    HISTORY("M5,3h14v18l-3,-2 -4,2 -4,-2 -3,2z M8,7h8 M8,11h8 M8,15h5"),
    LIST("M8,6h12 M8,12h12 M8,18h12 M3,6h1 M3,12h1 M3,18h1"),
    PERSON("M12,3a4,4 0,1 0,0 8a4,4 0,0 0,0 -8 M4,21v-2a8,5 0,0 1,16 0v2z"),
    SEARCH("M10,3a7,7 0,1 0,0 14a7,7 0,0 0,0 -14 M15,15l6,6"),
    CLOSE("M6,6l12,12 M18,6L6,18"),
    BELL("M5,17h14l-2,-3V9a5,5 0,0 0,-10 0v5z M10,20h4 M12,2v2"),
    CART("M2,3h3l3,12h11l3,-9H6 M10,19a1,1 0,1 0,0 2a1,1 0,0 0,0 -2 M18,19a1,1 0,1 0,0 2a1,1 0,0 0,0 -2"),
    STORE("M3,3h18v5l-2,3 -3,-2 -4,2 -4,-2 -3,2 -2,-3z M5,11v10h14V11 M9,21v-6h6v6"),
    CALENDAR("M5,4h14v17H5z M8,2v5 M16,2v5 M5,10h14 M9,14h6 M9,17h4"),
    ARROW("M4,12h16 M14,6l6,6 -6,6"),
}

private val homeVectors = HomeSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(symbol.path).toNodes(), stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()
}

@Composable
private fun HomeIcon(symbol: HomeSymbol, description: String? = null, tint: Color = LocalContentColor.current) {
    Icon(homeVectors.getValue(symbol), contentDescription = description, modifier = Modifier.size(24.dp), tint = tint)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
private fun HomePreview() {
    SmartShoppingTheme(darkTheme = false) {
        HomeContent("preview@example.com", 3, "Cân bằng", {}, {}, {})
    }
}
