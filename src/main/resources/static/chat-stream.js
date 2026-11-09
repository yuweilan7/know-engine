async function readChatEvents(stream, onEvent) {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let type = '';
    let data = [];
    function line(text) {
        if (text === '') {
            if (data.length) {
                const event = JSON.parse(data.join('\n'));
                if (event.protocolVersion !== 1 || type !== event.type) throw new Error('聊天事件格式无效');
                onEvent(event);
            }
            type = '';
            data = [];
            return;
        }
        if (text.startsWith(':')) return;
        const separator = text.indexOf(':');
        const field = separator < 0 ? text : text.slice(0, separator);
        let value = separator < 0 ? '' : text.slice(separator + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'data') data.push(value);
        if (field === 'event') type = value;
    }
    try {
        while (true) {
            const {done, value} = await reader.read();
            buffer += decoder.decode(value, {stream: !done});
            let start = 0;
            for (let i = 0; i < buffer.length; i++) {
                if (buffer[i] !== '\r' && buffer[i] !== '\n') continue;
                if (buffer[i] === '\r' && i === buffer.length - 1 && !done) break;
                line(buffer.slice(start, i));
                if (buffer[i] === '\r' && buffer[i + 1] === '\n') i++;
                start = i + 1;
            }
            buffer = buffer.slice(start);
            if (done) return;
        }
    } finally {
        await reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}
