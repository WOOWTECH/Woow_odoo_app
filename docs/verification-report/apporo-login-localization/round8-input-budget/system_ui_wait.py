"""Fail closed except for one explicitly authorized System UI ANR Wait action."""
import re

APP = 'com.apporo.odoo.debug'
TITLE = "System UI isn't responding"


def rectangle(node, screen):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not match:
        raise ValueError('Malformed modal bounds')
    x1, y1, x2, y2 = map(int, match.groups())
    if not (0 <= x1 < x2 <= screen[0] and 0 <= y1 < y2 <= screen[1]):
        raise ValueError('Modal bounds outside fresh screenshot')
    return x1, y1, x2, y2


def modal_present(root):
    nodes = list(root.iter('node'))
    return any(n.get('package') != APP or 'Dialog' in n.get('class', '')
               or n.get('resource-id', '').startswith('android:id/aerr_')
               or n.get('resource-id') == 'android:id/alertTitle' for n in nodes)


def wait_center(root, screen, initial, used):
    """No coordinates are retained; caller must supply the latest saved XML/PNG."""
    if not modal_present(root):
        return None
    if not initial or used:
        raise ValueError('Modal outside initial readiness or after single Wait')
    nodes = list(root.iter('node'))
    if not nodes or any(n.get('package') != 'android' for n in nodes):
        raise ValueError('Unknown modal package or mixed windows')
    titles = [n for n in nodes if n.get('resource-id') == 'android:id/alertTitle']
    waits = [n for n in nodes if n.get('resource-id') == 'android:id/aerr_wait' or n.get('text') == 'Wait']
    closes = [n for n in nodes if n.get('resource-id') == 'android:id/aerr_close']
    if len(titles) != 1 or len(waits) != 1 or len(closes) != 1:
        raise ValueError('Ambiguous modal title/buttons')
    title, wait, close = titles[0], waits[0], closes[0]
    if (title.get('text') != TITLE or title.get('class') != 'android.widget.TextView'
            or title.get('enabled') != 'true' or title.get('clickable') != 'false'):
        raise ValueError('Not the exact observed System UI ANR title')
    for node, text, resource in ((wait, 'Wait', 'android:id/aerr_wait'),
                                 (close, 'Close app', 'android:id/aerr_close')):
        if (node.get('text') != text or node.get('resource-id') != resource
                or node.get('class') != 'android.widget.Button'
                or node.get('enabled') != 'true' or node.get('clickable') != 'true'):
            raise ValueError('Invalid observed ANR button')
    if [n for n in nodes if n.get('clickable') == 'true'] != [close, wait]:
        raise ValueError('Unknown or ambiguous modal controls')
    boxes = {id(n): rectangle(n, screen) for n in nodes}
    for parent in root.iter('node'):
        a, b, c, d = boxes[id(parent)]
        for child in parent:
            x1, y1, x2, y2 = boxes[id(child)]
            if not (a <= x1 < x2 <= c and b <= y1 < y2 <= d):
                raise ValueError('Modal child outside parent')
    x1, y1, x2, y2 = boxes[id(wait)]
    a, b, c, d = boxes[id(close)]
    if max(x1, a) < min(x2, c) and max(y1, b) < min(y2, d):
        raise ValueError('Wait overlaps Close app')
    return (x1 + x2) // 2, (y1 + y2) // 2
