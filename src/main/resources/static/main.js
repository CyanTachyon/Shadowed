let socket;
let currentUser = null;
let privateKey = null;
let friendPublicKeys = {};
let authKey = null;
let currentUserAuthToken = null;
let chatKeys = {};
let currentChatMessages = [];

function showToast(message, type = 'info', onClick = null)
{
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerText = message;
    if (onClick)
    {
        toast.onclick = onClick;
        toast.style.cursor = 'pointer';
    }
    container.appendChild(toast);
    setTimeout(() =>
    {
        toast.style.animation = 'fadeOut 0.3s ease forwards';
        toast.addEventListener('animationend', () => toast.remove());
    }, 3000);
}

const refreshChats = () => socket.send(`get_chats`);

async function handlePacket(data)
{
    switch (data.packet)
    {
        case 'notify': {
            const type = data.type === 'ERROR' ? 'error' : (data.type === 'INFO' ? 'success' : 'info');
            showToast(data.message, type);
            if (data.packet === 'notify' && data.type === 'INFO' && (data.message.includes("Friend added") || data.message.includes("Group created") || data.message.includes("Chat renamed")))
            {
                refreshChats();
            }
            return;
        }
        case 'login_success': {
            try
            {
                const user = data.user;
                window.username = user.username;
                const passwordKey = await deriveKeyFromPassword(window.password, user.username);
                privateKey = await decryptPrivateKey(user.privateKey, passwordKey);

                if (!privateKey)
                {
                    showToast("Failed to decrypt private key. Wrong password?", "error");
                    return;
                }
                console.log("Private key decrypted successfully");
            }
            catch (e)
            {
                console.error("Login crypto error", e);
                showToast("Crypto error during login", "error");
                return;
            }
            currentUser = data.user;
            document.getElementById('auth-overlay').style.display = 'none';
            document.getElementById('chat-interface').style.display = 'flex';
            const userNameEl = document.getElementById('current-user-name');
            if (userNameEl) userNameEl.innerText = currentUser.username;
            updateCurrentUserAvatar();
            refreshChats();
            return;
        }
        case 'public_key_by_username': {
            if (window.pendingKeyRequests && window.pendingKeyRequests[data.username])
            {
                window.pendingKeyRequests[data.username](data.publicKey);
                delete window.pendingKeyRequests[data.username];
            }
            return;
        }
        case 'chats_list': {
            handleChatsList(data.chats);
            return;
        }
        case 'messages_list': {
            currentChatMessages.push(...data.messages);
            if (data.chatId === currentChatId) window.hasMoreMessages = data.messages.length > 0;
            await renderMessages(true);
            return;
        }
        case 'receive_message': {

            if (data.message.chatId === currentChatId)
            {
                currentChatMessages.push(data.message);
                await renderMessages(false);
            }
            else showToast("New message in Chat " + data.message.chatId, 'info', () => selectChat(data.message))
            return;
        }
        case 'friends_list': {
            renderFriendsForSelection(data.friends);
            return;
        }
        case 'chat_details': {
            renderChatSettings(data);
            return;
        }
        case 'friend_added': {
            showToast(data.message, 'success');
            refreshChats();
            window.pendingChatToOpen = data.chatId;
            return;
        }
        case 'unread_count': {
            const chatId = data.chatId;
            const unreadCount = data.unread;
            const chatDiv = document.getElementById(`chat-${chatId}`);
            if (chatDiv)
            {
                const badge = chatDiv.querySelector('.unread-badge');
                if (unreadCount > 0)
                {
                    if (badge) badge.innerText = unreadCount;
                    else
                    {
                        const newBadge = document.createElement('div');
                        newBadge.className = 'unread-badge';
                        newBadge.innerText = unreadCount;
                        chatDiv.querySelector('.unread-parent').appendChild(newBadge);
                    }
                }
                else if (badge) badge.remove();
            }
            return;
        }
    }
}

async function register()
{
    const username = document.getElementById('reg-username').value;
    const password = document.getElementById('reg-password').value;

    if (!username || !password)
    {
        showToast("Please fill all fields", "error");
        return;
    }

    try
    {
        const serverKey = await fetchAuthParams();
        const keyPair = await generateKeyPair();
        const publicKeyStr = await exportPublicKey(keyPair.publicKey);
        const passwordKey = await deriveKeyFromPassword(password, username);
        const encryptedPrivateKeyStr = await encryptPrivateKey(keyPair.privateKey, passwordKey);
        const authHash = await hashPasswordWithServerKey(password, serverKey);

        const response = await fetch('/api/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                username: username,
                password: authHash,
                publicKey: publicKeyStr,
                privateKey: encryptedPrivateKeyStr
            })
        });

        const result = await response.json();
        if (result.success)
        {
            showLogin();
            await login(username, password);
        }
        else showToast("Registration failed: " + result.message, "error");
    }
    catch (e)
    {
        console.error(e);
        showToast("Error during registration: " + e.message, "error");
    }
}

async function login(username, password)
{
    username = username || document.getElementById('login-username').value;
    password = password || document.getElementById('login-password').value;
    if (!username || !password) return;
    window.password = password;
    window.username = username;
    try
    {
        const serverKey = await fetchAuthParams();
        const authHash = await hashPasswordWithServerKey(password, serverKey);
        currentUserAuthToken = authHash;
        const packet = {
            username: username,
            password: authHash
        };
        socket.send(`login\n${JSON.stringify(packet)}`);
    }
    catch (e)
    {
        console.error("Login prep failed", e);
        showToast("Login failed to initialize", "error");
    }
}

async function handleChatsList(chats)
{
    window.chats = chats; // Store for lookup
    const list = document.getElementById('friend-list');
    list.innerHTML = '';

    for (const chat of chats)
    {
        let unreadBadgeHtml = '';
        if (chat.unreadCount && chat.unreadCount > 0)
            unreadBadgeHtml = `<div class="unread-badge">${chat.unreadCount}</div>`;

        if (!chatKeys[chat.chatId])
        {
            const aesKey = await decryptSymmetricKey(chat.key, privateKey);
            if (aesKey) chatKeys[chat.chatId] = aesKey;
            else console.error("Failed to decrypt key for chat", chat.chatId);
        }
        const div = document.createElement('div');
        div.className = `friend-item ${currentChatId === chat.chatId ? 'active' : ''}`;
        div.id = `chat-${chat.chatId}`;
        const isGroup = !chat.isPrivate;
        const displayName = chat.name || 'Chat ' + chat.chatId;

        let iconHtml;
        if (isGroup)
        {
            iconHtml = `<div class="chat-icon unread-parent" style="position: relative;">
                <svg class="group-icon-svg" viewBox="0 0 24 24"><path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"/></svg>
                ${unreadBadgeHtml}
            </div>`;
        }
        else
        {
            const otherId = chat.parsedOtherIds && chat.parsedOtherIds.length > 0 ? chat.parsedOtherIds[0] : null;
            const initial = displayName && displayName.length > 0 ? displayName[0].toUpperCase() : '?';

            if (otherId)
            {
                const avatarUrl = fetchAvatarUrl(otherId);

                iconHtml = `
                    <div style="position: relative; margin-right: 12px; width: 40px; height: 40px;" class="unread-parent">
                        <img class="user-avatar" style="width: 100%; height: 100%; border-radius: 50%; margin: 0;" src="${avatarUrl}" alt="avatar">
                        <div class="chat-icon" style="background: var(--primary-color); color: white; border-radius: 50%; font-size: 1.2rem; display: none; margin: 0; width: 100%; height: 100%; flex: 1; align-items: center; justify-content: center;">${initial}</div>
                        ${unreadBadgeHtml}
                    </div>`;
            }
            else
            {
                iconHtml = `<div class="chat-icon unread-parent" style="background: var(--primary-color); color: white; border-radius: 50%; font-size: 1.2rem; position: relative;">
                    ${initial}
                    ${unreadBadgeHtml}
                </div>`;
            }
        }

        div.innerHTML = `
            ${iconHtml}
            <div style="flex: 1; overflow: hidden;">
                <span class="name" style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${displayName}</span>
                <span class="status">${isGroup ? (chat.parsedOtherIds ? chat.parsedOtherIds.length + 1 : chat.parsedOtherNames.length + 1) + ' members' : 'Private chat'}</span>
            </div>
        `;
        div.onclick = () => selectChat(chat);
        list.appendChild(div);
    }

    // If there's a pending chat to open (from add_friend), open it now
    if (window.pendingChatToOpen)
    {
        const chatToOpen = chats.find(c => c.chatId === window.pendingChatToOpen);
        if (chatToOpen) selectChat(chatToOpen);
        window.pendingChatToOpen = null;
    }
}

let currentChatId = null;

function selectChat(chat)
{
    closeChatSettings();
    currentChatId = chat.chatId;
    currentChatMessages = [];
    const chatWithEl = document.getElementById('chat-with');
    const displayName = chat.name || `Chat ${currentChatId}`;
    const isGroup = !chat.isPrivate;
    let avatarHtml = '';
    if (isGroup) avatarHtml = `<svg class="group-icon-svg" style="width: 24px; height: 24px; margin-right: 8px;" viewBox="0 0 24 24"><path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z" fill="currentColor"/></svg>`;
    else
    {
        const otherId = chat.parsedOtherIds && chat.parsedOtherIds.length > 0 ? chat.parsedOtherIds[0] : null;
        if (otherId)
        {
            const avatarUrl = fetchAvatarUrl(otherId);
            avatarHtml = `<img src="${avatarUrl}" style="width: 28px; height: 28px; border-radius: 50%; margin-right: 8px; vertical-align: middle; object-fit: cover;" alt="avatar">`;
        }
    }

    chatWithEl.innerHTML = `<div style="display: flex; align-items: center;">${avatarHtml}<span>${displayName}</span></div>`;
    document.getElementById('chat-settings-toggle').style.display = 'block';
    document.getElementById('message-input').disabled = false;
    document.getElementById('message-input').value = '';

    // 更新active状态
    const items = document.querySelectorAll('.friend-item');
    items.forEach(item => item.classList.remove('active'));
    const selectedItem = document.getElementById(`chat-${currentChatId}`);
    if (selectedItem) selectedItem.classList.add('active');

    // 清空聊天消息，显示loading
    document.getElementById('messages-container').innerHTML = '<div style="padding: 20px; text-align: center; color: var(--secondary-color);" id="placeholder">Loading messages...</div>';

    // Reset pagination
    window.currentChatOffset = 0;
    window.hasMoreMessages = true; // Assume true initially

    loadChatMessages(currentChatId, 0);

    enterChatView();
}

function loadChatMessages(chatId, offset)
{
    const count = 50;
    const packet = {
        chatId: chatId,
        begin: offset,
        count: count
    };
    socket.send(`get_messages\n${JSON.stringify(packet)}`);
}

function enterChatView()
{
    document.body.classList.add('view-chat');
    document.body.classList.remove('view-settings');
    const state = { view: 'chat', chatId: currentChatId };
    if (history.state && history.state.view === 'chat' && history.state.chatId === currentChatId) history.replaceState(state, '');
    else history.pushState(state, '');
}

function leaveChatView()
{
    if (history.state && history.state.view === 'chat') history.back();
    else
    {
        document.body.classList.remove('view-chat');
        document.body.classList.remove('view-settings');
    }
}

window.onpopstate = function (event)
{
    const state = event.state;
    if (!state || state.view === 'list')
    {
        document.body.classList.remove('view-chat');
        document.body.classList.remove('view-settings');
    }
    else if (state.view === 'chat')
    {
        document.body.classList.add('view-chat');
        document.body.classList.remove('view-settings');
        if (state.chatId && state.chatId !== currentChatId)
        {
            const chat = window.chats.find(c => c.chatId === state.chatId);
            if (chat) selectChat(chat);
        }
    }
    else if (state.view === 'settings')
    {
        document.body.classList.add('view-settings');
        document.getElementById('chat-settings-panel').style.display = 'flex';
    }
};

async function renderMessages(insertAtTop)
{
    const container = document.getElementById('messages-container');
    const oldScrollHeight = container.scrollHeight;
    const oldScrollTop = container.scrollTop;
    container.onscroll = null;
    container.querySelector("#placeholder")?.remove()
    currentChatMessages = Array.from(new Set(currentChatMessages)).sort((a, b) => a.id - b.id).filter((msg => msg.chatId === currentChatId));
    const needRender = currentChatMessages.map((it, index) => [it, currentChatMessages[index + 1]]).filter(([msg, nextMsg]) => document.getElementById(`message-${msg.id}`) === null).reverse();
    for (const msg of needRender)
    {
        const ele = await createMessageElement(msg[0]);
        if (msg[1]) container.insertBefore(ele, document.getElementById(`message-${msg[1].id}`));
        else container.appendChild(ele);
    }
    const newScrollHeight = container.scrollHeight;
    if (insertAtTop) container.scrollTop = newScrollHeight - oldScrollHeight + oldScrollTop;
    else if (oldScrollTop > oldScrollHeight - 5 - container.clientHeight) container.scrollTop = container.scrollHeight;
    else container.scrollTop = oldScrollTop
    console.log(container.scrollTop, container.scrollHeight);
    if (currentChatMessages.length === 0) container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--secondary-color);" id="placeholder">No messages yet. Start the conversation!</div>';
    container.onscroll = handleContainerScroll;
}

let loadMoreWaiter = null;
async function handleContainerScroll()
{
    const container = document.getElementById('messages-container');
    if (container.scrollTop === 0 && window.hasMoreMessages)
    {
        if (loadMoreWaiter) await loadMoreWaiter;
        loadMoreWaiter = new Promise(resolve => setTimeout(resolve, 1000));
        window.currentChatOffset += 50;
        loadChatMessages(currentChatId, window.currentChatOffset);
    }
}

const addFriendModal = () => document.getElementById('add-friend-modal').style.display = 'flex';
const closeModal = () => document.getElementById('add-friend-modal').style.display = 'none';
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 3;
function connectWebSocket()
{
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(`${protocol}//${window.location.host}/api/socket`);

    socket.onopen = () =>
    {
        console.log("Connected to WebSocket");
        reconnectAttempts = 0;
        if (window.password && window.username) login(window.username, window.password);
    };

    socket.onmessage = async (event) =>
    {
        const data = JSON.parse(event.data);
        await handlePacket(data);
    };

    socket.onerror = socket.onclose = () =>
    {
        console.log("Disconnected");
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS)
        {
            reconnectAttempts++;
            console.log(`Reconnecting... Attempt ${reconnectAttempts}`);
            setTimeout(connectWebSocket, 3000); // Wait 3s before retry
        }
        else
        {
            showToast("Connection lost. Logging out...", "error");
            setTimeout(logout, 2000);
        }
    };
}

async function createMessageElement(msg)
{
    const div = document.createElement('div');
    const myId = (typeof currentUser.id === 'object' && currentUser.id.value) ? currentUser.id.value : currentUser.id;
    const isMe = msg.senderId === myId;
    div.className = `message ${isMe ? 'sent' : 'received'}`;
    div.id = `message-${msg.id}`;
    if (!isMe && window.chats)
    {
        const chat = window.chats.find(c => c.chatId === msg.chatId);
        if (chat && !chat.isPrivate)
        {
            const senderDiv = document.createElement('div');
            senderDiv.className = 'message-sender';
            senderDiv.innerText = msg.senderName || `User ${msg.senderId}`;
            div.appendChild(senderDiv);

            const avatarUrl = fetchAvatarUrl(msg.senderId);
            const avatarImg = document.createElement('img');
            avatarImg.style.position = 'absolute';
            avatarImg.className = 'user-avatar';
            avatarImg.style.top = '0';
            avatarImg.style.left = '-40px';
            avatarImg.src = avatarUrl;
            div.appendChild(avatarImg);

            div.style.marginLeft = '40px';
        }
    }


    const contentDiv = document.createElement('div');

    try
    {
        let chatKey = chatKeys[msg.chatId];
        if (!chatKey) contentDiv.innerText = "[Key Not Available]";
        else if (msg.type.toLowerCase() === 'text') contentDiv.innerText = await decryptMessageString(msg.content, chatKey);
        else if (msg.type.toLowerCase() === 'image')
        {
            contentDiv.innerText = "[Loading Image...]";
            fetch(`/api/file/${msg.id}`).then(res => res.text()).then(async base64 =>
            {
                const imageData = await decryptMessageBytes(base64, chatKey);
                const blob = new Blob([imageData]);
                const url = URL.createObjectURL(blob);
                const img = document.createElement('img');
                img.src = url;
                img.style.maxWidth = '100%';
                img.style.maxHeight = '300px';
                contentDiv.innerHTML = '';
                contentDiv.appendChild(img);
            });
        }
    }
    catch (e)
    {
        contentDiv.innerText = "[Decryption Error]";
        console.error("Message decryption error", e);
    }

    div.appendChild(contentDiv);
    const meta = document.createElement('div');
    meta.style.fontSize = "0.7em";
    if (isMe) meta.style.textAlign = "right";
    else meta.style.textAlign = "left";
    meta.style.marginTop = "4px";
    meta.style.opacity = "0.7";
    let statusText = "";
    meta.innerText = new Date(msg.time).toLocaleString() + statusText;
    div.appendChild(meta);
    return div;
}

async function addFriend()
{
    const usernameInput = document.getElementById('add-friend-username');
    const username = usernameInput.value.trim();

    if (!username) return showToast("Please enter a username", "error");

    try
    {
        const targetPublicKeyStr = await fetchPublicKeyByUsername(username);
        if (!targetPublicKeyStr)
        {
            showToast("User not found or no key", "error");
            return;
        }
        const targetPublicKey = await importPublicKey(targetPublicKeyStr);

        let myPublicKey = friendPublicKeys[currentUser.id.value];
        if (!myPublicKey && currentUser.publicKey)
            myPublicKey = await importPublicKey(currentUser.publicKey);
        if (!myPublicKey)
            throw new Error("My public key unavailable");

        const symmetricKey = await generateSymmetricKey();
        const encryptedKeyForFriend = await encryptSymmetricKey(symmetricKey, targetPublicKey);
        const encryptedKeyForSelf = await encryptSymmetricKey(symmetricKey, myPublicKey);
        const packet = {
            targetUsername: username,
            keyForFriend: encryptedKeyForFriend,
            keyForSelf: encryptedKeyForSelf
        };
        socket.send(`add_friend\n${JSON.stringify(packet)}`);
        usernameInput.value = '';
        closeModal();
    }
    catch (e)
    {
        console.error("Add friend error", e);
        showToast("Failed: " + e.message, "error");
    }
}

function createGroupModal()
{
    document.getElementById('create-group-modal').style.display = 'flex';
    document.getElementById('group-members-list').innerHTML = '<div style="padding: 10px; text-align: center; color: var(--secondary-color);" id="placeholder">Loading friends...</div>';
    document
    socket.send("get_friends");
}

function renderFriendsForSelection(friends)
{
    const container = document.getElementById('group-members-list');
    const isInviting = window.invitingToChat;
    document.getElementById('create-group-modal').style.display = 'flex';

    const modalTitle = document.querySelector('#create-group-modal h3');
    if (modalTitle)
        modalTitle.innerText = isInviting ? 'Invite Member' : 'Create Group Chat';

    const groupNameInput = document.getElementById('group-name');
    if (groupNameInput)
        groupNameInput.style.display = isInviting ? 'none' : 'block';

    let availableFriends = friends;
    if (isInviting && window.currentChatDetails)
    {
        const existingUsernames = window.currentChatDetails.members.map(m => m.username);
        availableFriends = friends.filter(f => !existingUsernames.includes(f.username));
    }

    if (availableFriends.length === 0)
    {
        container.innerHTML = `<div style="padding: 10px; text-align: center; color: var(--secondary-color);">${isInviting ? 'All friends are already members!' : 'No friends found. Add someone first!'}</div>`;
        return;
    }

    container.innerHTML = '';
    availableFriends.forEach(friend =>
    {
        const div = document.createElement('div');
        div.className = 'friend-select-item';

        if (isInviting)
        {
            div.onclick = async () =>
            {
                await inviteMemberToChat(friend.username);
                window.invitingToChat = false;
                closeGroupModal();
            };
            div.innerHTML = `
                <img src="${fetchAvatarUrl(friend.id)}" class="avatar" style="width:24px; height:24px; background:var(--border-color); border-radius:50%; display:inline-flex; align-items:center; justify-content:center; font-size:12px; margin-right:8px;" alt="avatar"/> 
                <span>${friend.username}</span>
            `;
        }
        else
        {
            // For group creation: checkbox selection
            div.onclick = (e) =>
            {
                if (e.target.tagName !== 'INPUT')
                {
                    const cb = div.querySelector('input');
                    cb.checked = !cb.checked;
                }
            };
            div.innerHTML = `
                <input type="checkbox" data-username="${friend.username}" data-id="${friend.id}">
                <img src="${fetchAvatarUrl(friend.id)}" class="avatar" style="width:24px; height:24px; background:var(--border-color); border-radius:50%; display:inline-flex; align-items:center; justify-content:center; font-size:12px; margin-right:8px;" alt="avatar"/>
                <span>${friend.username}</span>
            `;
        }
        container.appendChild(div);
    });

    const createButton = document.querySelector('#create-group-modal .button');
    if (createButton && isInviting) createButton.style.display = 'none';
}

function closeGroupModal()
{
    document.getElementById('create-group-modal').style.display = 'none';
    window.invitingToChat = false;
    const modalTitle = document.querySelector('#create-group-modal h3');
    if (modalTitle) modalTitle.innerText = 'Create Group Chat';
    const groupNameInput = document.getElementById('group-name');
    if (groupNameInput)
    {
        groupNameInput.style.display = 'block';
        const createButton = document.querySelector('#create-group-modal .button');
        if (createButton) createButton.style.display = 'inline-block';
    }
}

async function createGroup()
{
    const groupName = document.getElementById('group-name').value.trim();
    const checkedBoxes = document.querySelectorAll('#group-members-list input[type="checkbox"]:checked');

    // Group chat requires at least 3 people (self + 2 others)
    if (checkedBoxes.length < 2)
    {
        showToast("Group chat requires at least 3 members (including yourself)", "error");
        return;
    }

    try
    {
        const memberUsernames = Array.from(checkedBoxes).map(cb => cb.dataset.username);

        // Fetch public keys for all members
        const publicKeys = {};
        for (const username of memberUsernames)
        {
            const pubKey = await fetchPublicKeyByUsername(username);
            if (!pubKey)
            {
                showToast(`User not found: ${username}`, "error");
                return;
            }
            publicKeys[username] = await importPublicKey(pubKey);
        }

        // Get my own public key
        let myPublicKey = null;
        if (currentUser.publicKey)
            myPublicKey = await importPublicKey(currentUser.publicKey);
        if (!myPublicKey)
            throw new Error("My public key unavailable")
        const symmetricKey = await generateSymmetricKey();
        const encryptedKeys = {};
        encryptedKeys[currentUser.username] = await encryptSymmetricKey(symmetricKey, myPublicKey);

        // Encrypt for all other members
        for (const username of memberUsernames)
            if (username !== currentUser.username)
                encryptedKeys[username] = await encryptSymmetricKey(symmetricKey, publicKeys[username]);

        const packet = {
            name: groupName || null,
            memberUsernames: memberUsernames,
            encryptedKeys: encryptedKeys
        };
        socket.send(`create_group\n${JSON.stringify(packet)}`);
        document.getElementById('group-name').value = '';
        closeGroupModal();

    }
    catch (e)
    {
        console.error("Create group error", e);
        showToast("Failed: " + e.message, "error");
    }
}

function toggleChatSettings()
{
    const panel = document.getElementById('chat-settings-panel');
    const isVisible = panel.style.display === 'flex';
    if (isVisible || document.body.classList.contains('view-settings')) closeChatSettings();
    else
    {
        panel.style.display = 'flex';
        socket.send(`get_chat_details\n${JSON.stringify({ chatId: currentChatId })}`);
        const state = { view: 'settings', chatId: currentChatId };
        if (history.state && history.state.view === 'settings' && history.state.chatId === currentChatId) history.replaceState(state, '');
        else history.pushState(state, '');
    }
}

function closeChatSettings()
{
    const panel = document.getElementById('chat-settings-panel');
    if (!panel) return;
    panel.style.display = 'none';
    if (history.state && history.state.view === 'settings') history.back();
    renderChatSettings({
        chat: {
            id: 0,
            name: '',
            isPrivate: true,
            ownerId: null,
            members: []
        }
    });
}

function renderChatSettings(details)
{
    const container = document.getElementById('settings-content');
    const chat = details.chat;
    const myId = (currentUser.id.value || currentUser.id);
    const isOwner = chat.ownerId === myId;
    const isPrivate = chat.isPrivate;

    let html = '';

    // Only show chat name and rename for group chats
    if (!isPrivate)
    {
        html += `
            <div class="settings-section">
                <div class="section-title">Chat Name</div>
                <div style="font-weight: bold; margin-bottom: 5px;" id="current-chat-name-display">${chat.name || 'Chat ' + chat.id}</div>
                ${isOwner ? `
                    <div class="rename-group">
                        <input type="text" id="new-chat-name" placeholder="New name">
                        <button onclick="updateChatName()">Rename</button>
                    </div>
                ` : ''}
            </div>
        `;
    }

    // Members section for all chats
    html += `
        <div class="settings-section">
            <div class="section-title">Members (${chat.members.length})</div>
            ${!isPrivate ? `
                <button class="button" onclick="showInviteMemberModal()" style="width: 100%; margin-bottom: 10px;">Invite Member</button>
            ` : ''}
            <div class="member-list">
                ${chat.members.map(m => `
                    <div class="member-item" onclick="startPrivateChat('${m.username}')">
                        <div style="width: 32px; height: 32px; margin-right: 10px; position: relative; flex-shrink: 0;">
                            <img src="${fetchAvatarUrl(m.id)}" style="width: 100%; height: 100%; border-radius: 50%; object-fit: cover;" alt="avatar">
                            <div class="avatar" style="width: 100%; height: 100%; position: absolute; top:0; left:0; display: none;">${m.username[0].toUpperCase()}</div>
                        </div>
                        <span style="overflow: hidden; text-overflow: ellipsis;">${m.username} ${m.id === chat.ownerId ? '<span style="color:var(--primary-color); font-size:0.8em">(Owner)</span>' : ''} ${m.id === myId ? '<span style="color:var(--secondary-color); font-size:0.8em">(Me)</span>' : ''}</span>
                    </div>
                `).join('')}
            </div>
        </div>
    `;

    // Store current chat details for invite functionality
    window.currentChatDetails = chat;

    container.innerHTML = html;
}

async function updateChatName()
{
    const newName = document.getElementById('new-chat-name').value.trim();
    if (!newName) return;
    socket.send(`rename_chat\n${JSON.stringify({
        chatId: currentChatId,
        newName: newName
    })}`);
}

function startPrivateChat(username)
{
    if (username === currentUser.username) return;
    document.getElementById('chat-settings-panel').style.display = 'none';
    document.getElementById('add-friend-username').value = username;
    addFriend();
}

async function fetchPublicKeyByUsername(username)
{
    try
    {
        const res = await fetch('/api/user/publicKey?username=' + encodeURIComponent(username), {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
        });
        if (res.status === 200)
        {
            const data = await res.json();
            return data.publicKey;
        }
        return null;
    }
    catch (e)
    {
        console.log(e);
        return null;
    }
}

async function sendMessage()
{
    const input = document.getElementById('message-input');
    const text = input.value.trim();
    if (!text || !currentChatId) return;

    const chatKey = chatKeys[currentChatId];
    if (!chatKey)
    {
        showToast("Chat key not loaded!", "error");
        return;
    }

    try
    {
        const encrypted = await encryptMessageString(text, chatKey);
        const packet = {
            chatId: currentChatId,
            message: encrypted,
            type: 'text',
        };
        socket.send(`send_message\n${JSON.stringify(packet)}`);
        input.value = '';
    }
    catch (e)
    {
        console.error("Encrypt failed", e);
        showToast("Failed to encrypt message: " + e.message, "error");
    }
}

async function sendImage()
{
    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = 'image/*';
    fileInput.onchange = async () =>
    {
        const file = fileInput.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = async (e) =>
        {
            const arrayBuffer = e.target.result;
            const chatKey = chatKeys[currentChatId];
            if (!chatKey)
            {
                showToast("Chat key not loaded!", "error");
                return;
            }

            try
            {
                const encrypted = await encryptMessageBytes(arrayBuffer, chatKey);
                await fetch("/api/send_file", {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'text/plain',
                        'X-Auth-Token': currentUserAuthToken,
                        'X-Chat-Id': currentChatId,
                        'X-Message-Type': 'image',
                        'X-Auth-User': currentUser.username
                    },
                    body: encrypted
                })
            }
            catch (e)
            {
                console.error("Encrypt failed", e);
                showToast("Failed to encrypt image: " + e.message, "error");
            }
        };
        reader.readAsArrayBuffer(file);
    }
    fileInput.click();
}

async function fetchAuthParams()
{
    if (authKey) return authKey;
    const response = await fetch('/api/auth/params');
    const data = await response.json();
    authKey = data.authKey;
    return authKey;
}

function showRegister()
{
    document.getElementById('login-box').style.display = 'none';
    document.getElementById('register-box').style.display = 'block';
}

function showLogin()
{
    document.getElementById('register-box').style.display = 'none';
    document.getElementById('login-box').style.display = 'block';
}

function toggleTheme()
{
    const html = document.documentElement;
    const current = html.getAttribute('data-theme');
    const next = current === 'dark' ? 'light' : 'dark';
    html.setAttribute('data-theme', next);
}

function handleKeyPress(e)
{
    if (e.key === 'Enter') sendMessage();
}

const logout = () => window.location.reload();

function showInviteMemberModal()
{
    console.log("showInviteMemberModal called");
    const modal = document.getElementById('create-group-modal');
    if (!modal)
    {
        console.error("Modal element not found!");
        return;
    }
    modal.style.display = 'flex';
    console.log("Modal display set to flex");
    const modalTitle = document.querySelector('#create-group-modal h3');
    if (modalTitle) modalTitle.innerText = 'Invite Member';
    const groupNameInput = document.getElementById('group-name');
    if (groupNameInput)
    {
        groupNameInput.style.display = 'none';
        const createButton = document.querySelector('#create-group-modal .button');
        if (createButton) createButton.style.display = 'none';
    }
    document.getElementById('group-members-list').innerHTML = '<div style="padding: 10px; text-align: center; color: var(--secondary-color);">Loading friends...</div>';
    window.invitingToChat = true;
    socket.send("get_friends");
}

async function inviteMemberToChat(username)
{
    if (!window.currentChatDetails || !currentChatId)
    {
        showToast("No chat selected", "error");
        return;
    }

    try
    {
        // Get the symmetric key for current chat
        const chatKey = chatKeys[currentChatId];
        if (!chatKey)
        {
            showToast("Chat key not available", "error");
            return;
        }

        // Fetch target user's public key
        const publicKeyStr = await fetchPublicKeyByUsername(username);
        if (!publicKeyStr)
        {
            showToast(`User not found: ${username}`, "error");
            return;
        }

        const publicKey = await importPublicKey(publicKeyStr);

        // Encrypt the chat's symmetric key with target user's public key
        const encryptedKey = await encryptSymmetricKey(chatKey, publicKey);

        // Send invite request
        const packet = {
            chatId: currentChatId,
            username: username,
            encryptedKey: encryptedKey
        };

        socket.send(`add_member_to_chat\n${JSON.stringify(packet)}`);

    }
    catch (e)
    {
        console.error("Invite member error", e);
        showToast("Failed to invite member: " + e.message, "error");
    }
}

// --- Avatar & Menu Logic ---

function toggleUserMenu()
{
    const menu = document.getElementById('user-menu');
    menu.classList.toggle('show');
    document.getElementById('add-menu').classList.remove('show');
}

function toggleAddMenu()
{
    const menu = document.getElementById('add-menu');
    menu.classList.toggle('show');
    document.getElementById('user-menu').classList.remove('show');
}

window.onclick = function (event)
{
    if (!event.target.closest('.avatar-container') && !event.target.closest('#user-menu'))
    {
        const menu = document.getElementById('user-menu');
        if (menu) menu.classList.remove('show');
    }
    if (!event.target.closest('.header-right') && !event.target.closest('#add-menu'))
    {
        const menu = document.getElementById('add-menu');
        if (menu) menu.classList.remove('show');
    }
};

function triggerAvatarUpload()
{
    document.getElementById('avatar-upload').click();
    document.getElementById('user-menu').classList.remove('show');
}

async function uploadAvatar()
{
    const fileInput = document.getElementById('avatar-upload');
    const file = fileInput.files[0];
    if (!file) return;

    const token = currentUserAuthToken;

    if (!token)
    {
        showToast("Authentication token missing. Please refresh and login again.", "error");
        return;
    }

    if (file.size > 2 * 1024 * 1024)
    {
        showToast("Image too large (max 2MB)", "error");
        return;
    }

    const formData = new FormData();
    formData.append("avatar", file);

    try
    {
        const response = await fetch('/api/user/avatar', {
            method: 'POST',
            headers: {
                'X-Auth-User': currentUser.username,
                'X-Auth-Token': token
            },
            body: formData
        });

        if (response.ok)
        {
            showToast("Avatar updated", "success");
            updateCurrentUserAvatar();
        }
        else showToast("Failed to upload avatar", "error");
    }
    catch (e)
    {
        console.error(e);
        showToast("Error uploading avatar", "error");
    }
}

function updateCurrentUserAvatar()
{
    if (!currentUser) return;
    const img = document.getElementById('current-user-avatar');
    if (img) img.src = `/api/user/${currentUser.id.value || currentUser.id}/avatar?t=${Date.now()}`;
}

const fetchAvatarUrl = (userId) => `/api/user/${userId}/avatar`;





connectWebSocket();