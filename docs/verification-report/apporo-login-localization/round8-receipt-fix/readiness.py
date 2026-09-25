"""Pure hierarchy predicates: never act on a stale or empty Compose tree."""
import re
import time
import xml.etree.ElementTree as ET

PACKAGE='com.apporo.odoo.debug'


def bounds(node):
    values=tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
    if len(values)!=4 or values[2]<=values[0] or values[3]<=values[1]:
        raise ValueError('Unusable observed bounds')
    return values


def field(root,label):
    candidates=[]
    for parent in root.iter():
        children=list(parent)
        for pos,node in enumerate(children[:-1]):
            if node.get('package')==PACKAGE and node.get('text')==label:
                following=children[pos+1]
                if following.get('package')==PACKAGE and following.get('class')=='android.widget.EditText' and following.get('enabled')=='true':
                    a,b=bounds(node),bounds(following)
                    if b[1]>=a[3]:candidates.append(following)
    return candidates[0] if len(candidates)==1 else None


def text_node(root,text,description=False):
    key='content-desc' if description else 'text'
    nodes=[n for n in root.iter('node') if n.get('package')==PACKAGE and n.get(key)==text and n.get('enabled')=='true']
    return nodes[0] if len(nodes)==1 else None


def form_ready(root,labels,button):
    return all(field(root,label) is not None for label in labels) and text_node(root,button) is not None


def safe_missing_credentials(root,username,password):
    user,pwd=field(root,username),field(root,password)
    return user is not None and pwd is not None and (not user.get('text','').strip() or not pwd.get('text','').strip())


def await_ready(fetch,predicate,deadline,clock=time.monotonic,pause=time.sleep):
    while clock()<deadline:
        snapshot=fetch(deadline)
        if clock()>=deadline:
            raise TimeoutError('Snapshot completed after readiness deadline; no action permitted')
        if predicate(snapshot[0]):return snapshot
        remaining=deadline-clock()
        if remaining>0:pause(min(.5,remaining))
    raise TimeoutError('Screen readiness deadline exhausted; no action permitted')
