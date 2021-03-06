package org.frozenbox.frozenchat.parser;

import android.util.Log;

import org.frozenbox.frozenchat.Config;
import org.frozenbox.frozenchat.entities.Account;
import org.frozenbox.frozenchat.entities.Contact;
import org.frozenbox.frozenchat.services.XmppConnectionService;
import org.frozenbox.frozenchat.xml.Element;
import org.frozenbox.frozenchat.xmpp.OnIqPacketReceived;
import org.frozenbox.frozenchat.xmpp.jid.InvalidJidException;
import org.frozenbox.frozenchat.xmpp.jid.Jid;
import org.frozenbox.frozenchat.xmpp.stanzas.IqPacket;

public class IqParser extends AbstractParser implements OnIqPacketReceived {

	public IqParser(XmppConnectionService service) {
		super(service);
	}

	public void rosterItems(Account account, Element query) {
		String version = query.getAttribute("ver");
		if (version != null) {
			account.getRoster().setVersion(version);
		}
		for (Element item : query.getChildren()) {
			if (item.getName().equals("item")) {
                Jid jid;
                try {
                    jid = Jid.fromString(item.getAttribute("jid"));
                } catch (final InvalidJidException e) {
                    // TODO: Handle this?
                    jid = null;
                }
                String name = item.getAttribute("name");
				String subscription = item.getAttribute("subscription");
				Contact contact = account.getRoster().getContact(jid);
				if (!contact.getOption(Contact.Options.DIRTY_PUSH)) {
					contact.setServerName(name);
					contact.parseGroupsFromElement(item);
				}
				if (subscription != null) {
					if (subscription.equals("remove")) {
						contact.resetOption(Contact.Options.IN_ROSTER);
						contact.resetOption(Contact.Options.DIRTY_DELETE);
						contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
					} else {
						contact.setOption(Contact.Options.IN_ROSTER);
						contact.resetOption(Contact.Options.DIRTY_PUSH);
						contact.parseSubscriptionFromElement(item);
					}
				}
				mXmppConnectionService.getAvatarService().clear(contact);
			}
		}
		mXmppConnectionService.updateConversationUi();
		mXmppConnectionService.updateRosterUi();
	}

	public String avatarData(IqPacket packet) {
		Element pubsub = packet.findChild("pubsub",
				"http://jabber.org/protocol/pubsub");
		if (pubsub == null) {
			return null;
		}
		Element items = pubsub.findChild("items");
		if (items == null) {
			return null;
		}
		return super.avatarData(items);
	}

	@Override
	public void onIqPacketReceived(Account account, IqPacket packet) {
		if (packet.hasChild("query", "jabber:iq:roster")) {
			final Jid from = packet.getFrom();
			if ((from == null) || (from.equals(account.getJid().toBareJid()))) {
				Element query = packet.findChild("query");
				this.rosterItems(account, query);
			}
		} else {
			if (packet.getFrom() == null) {
				Log.d(Config.LOGTAG, account.getJid().toBareJid().toString() + ": received iq with invalid from "+packet.toString());
				return;
			} else if (packet.hasChild("open", "http://jabber.org/protocol/ibb")
					|| packet.hasChild("data", "http://jabber.org/protocol/ibb")) {
				mXmppConnectionService.getJingleConnectionManager()
						.deliverIbbPacket(account, packet);
			} else if (packet.hasChild("query", "http://jabber.org/protocol/disco#info")) {
				IqPacket response = mXmppConnectionService.getIqGenerator()
						.discoResponse(packet);
				account.getXmppConnection().sendIqPacket(response, null);
			} else if (packet.hasChild("ping", "urn:xmpp:ping")) {
				IqPacket response = packet.generateRespone(IqPacket.TYPE_RESULT);
				mXmppConnectionService.sendIqPacket(account, response, null);
			} else {
				if ((packet.getType() == IqPacket.TYPE_GET)
						|| (packet.getType() == IqPacket.TYPE_SET)) {
					IqPacket response = packet.generateRespone(IqPacket.TYPE_ERROR);
					Element error = response.addChild("error");
					error.setAttribute("type", "cancel");
					error.addChild("feature-not-implemented",
							"urn:ietf:params:xml:ns:xmpp-stanzas");
					account.getXmppConnection().sendIqPacket(response, null);
				}
			}
		}
	}

}
